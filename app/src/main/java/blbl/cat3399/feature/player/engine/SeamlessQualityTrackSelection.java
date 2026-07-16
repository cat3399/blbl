package blbl.cat3399.feature.player.engine;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import blbl.cat3399.core.log.AppLog;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SeamlessQualitySelectionController {
    private final AtomicLong generation = new AtomicLong();
    private volatile int targetQn;
    private volatile int targetCodecid;

    void setTarget(int qn, int codecid) {
        targetQn = qn;
        targetCodecid = codecid;
        generation.incrementAndGet();
    }

    int getTargetQn() {
        return targetQn;
    }

    int getTargetCodecid() {
        return targetCodecid;
    }

    long getGeneration() {
        return generation.get();
    }
}

final class SeamlessQualityTrackSelectionFactory extends AdaptiveTrackSelection.Factory {
    private final SeamlessQualitySelectionController controller;

    SeamlessQualityTrackSelectionFactory(SeamlessQualitySelectionController controller) {
        this.controller = controller;
    }

    @Override
    protected AdaptiveTrackSelection createAdaptiveTrackSelection(
            TrackGroup group,
            int[] tracks,
            int type,
            BandwidthMeter bandwidthMeter,
            ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint> adaptationCheckpoints) {
        int codecid = commonCodecid(group, tracks);
        if (codecid > 0) {
            return new UserControlledQualityTrackSelection(group, tracks, bandwidthMeter, controller, codecid);
        }
        return super.createAdaptiveTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints);
    }

    private static int commonCodecid(TrackGroup group, int[] tracks) {
        int codecid = 0;
        for (int track : tracks) {
            int[] parsed = parseRepresentationId(group.getFormat(track).id);
            if (parsed == null) return 0;
            if (codecid == 0) {
                codecid = parsed[1];
            } else if (codecid != parsed[1]) {
                return 0;
            }
        }
        return codecid;
    }

    @Nullable
    static int[] parseRepresentationId(@Nullable String id) {
        if (id == null) return null;
        Matcher matcher = REPRESENTATION_ID.matcher(id);
        if (!matcher.find()) return null;
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final Pattern REPRESENTATION_ID =
            Pattern.compile("video_qn_(\\d+)_codec_(\\d+)_index_\\d+");
}

final class UserControlledQualityTrackSelection extends AdaptiveTrackSelection {
    private static final long MIN_BUFFER_TO_RETAIN_US = 1_500_000L;

    private final SeamlessQualitySelectionController controller;
    private final int codecid;
    private int selectedIndex;
    private @C.SelectionReason int selectionReason;
    private long appliedGeneration;

    UserControlledQualityTrackSelection(
            TrackGroup group,
            int[] tracks,
            BandwidthMeter bandwidthMeter,
            SeamlessQualitySelectionController controller,
            int codecid) {
        super(group, tracks, bandwidthMeter);
        this.controller = controller;
        this.codecid = codecid;
        this.selectedIndex = findTargetIndex();
        this.selectionReason = C.SELECTION_REASON_INITIAL;
        this.appliedGeneration = controller.getGeneration();
    }

    @Override
    public void updateSelectedTrack(
            long playbackPositionUs,
            long bufferedDurationUs,
            long availableDurationUs,
            List<? extends MediaChunk> queue,
            MediaChunkIterator[] mediaChunkIterators) {
        int targetIndex = findTargetIndex();
        long generation = controller.getGeneration();
        if (targetIndex != selectedIndex) {
            int oldQn = qnOf(getFormat(selectedIndex));
            int newQn = qnOf(getFormat(targetIndex));
            selectedIndex = targetIndex;
            selectionReason = C.SELECTION_REASON_ADAPTIVE;
            AppLog.INSTANCE.i(
                    "QualitySwitch",
                    "controller selected next chunk oldQn=" + oldQn + " newQn=" + newQn
                            + " codecid=" + codecid + " bufferedUs=" + bufferedDurationUs,
                    null);
        }
        appliedGeneration = generation;
    }

    @Override
    public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        long generation = controller.getGeneration();
        if (queue.isEmpty() || (generation == appliedGeneration && queueMatchesTarget(queue))) {
            return queue.size();
        }
        int targetQn = controller.getTargetQn();
        if (controller.getTargetCodecid() != codecid || targetQn <= 0) return queue.size();
        if (findExactTargetIndex() < 0) return queue.size();

        for (int i = 0; i < queue.size(); i++) {
            MediaChunk chunk = queue.get(i);
            long durationBeforeChunkUs = chunk.startTimeUs - playbackPositionUs;
            if (durationBeforeChunkUs < MIN_BUFFER_TO_RETAIN_US) continue;
            if (qnOf(chunk.trackFormat) != targetQn) {
                AppLog.INSTANCE.i(
                        "QualitySwitch",
                        "controller discard queue from=" + i + " size=" + queue.size()
                                + " retainUs=" + durationBeforeChunkUs + " targetQn=" + targetQn,
                        null);
                return i;
            }
        }
        return queue.size();
    }

    @Override
    public int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    public @C.SelectionReason int getSelectionReason() {
        return selectionReason;
    }

    @Override
    @Nullable
    public Object getSelectionData() {
        return null;
    }

    private int findTargetIndex() {
        int targetIndex = findExactTargetIndex();
        return targetIndex >= 0 ? targetIndex : selectedIndexInBounds();
    }

    private int findExactTargetIndex() {
        int targetQn = controller.getTargetQn();
        int targetCodecid = controller.getTargetCodecid();
        if (targetQn > 0 && targetCodecid == codecid) {
            for (int index = 0; index < length(); index++) {
                int[] parsed = SeamlessQualityTrackSelectionFactory.parseRepresentationId(getFormat(index).id);
                if (parsed != null && parsed[0] == targetQn && parsed[1] == codecid) return index;
            }
        }
        return -1;
    }

    private int selectedIndexInBounds() {
        return selectedIndex >= 0 && selectedIndex < length() ? selectedIndex : 0;
    }

    private boolean queueMatchesTarget(List<? extends MediaChunk> queue) {
        int targetQn = controller.getTargetQn();
        if (targetQn <= 0 || controller.getTargetCodecid() != codecid) return true;
        MediaChunk last = queue.get(queue.size() - 1);
        return qnOf(last.trackFormat) == targetQn;
    }

    private static int qnOf(Format format) {
        int[] parsed = SeamlessQualityTrackSelectionFactory.parseRepresentationId(format.id);
        return parsed == null ? -1 : parsed[0];
    }
}
