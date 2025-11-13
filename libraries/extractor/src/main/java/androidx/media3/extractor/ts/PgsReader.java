package androidx.media3.extractor.ts;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class PgsReader implements ElementaryStreamReader {

  private static final int SIGNATURE_LENGTH = 2;

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEF = 0x17;
  private static final int SECTION_TYPE_END = 0x80;
  private static final int SECTION_TYPE_PTS_DTS = 0x00;
  private static final int SECTION_PTS_DTS_SIZE = 8;

  private static final int STATE_SKIP_SIGNATURE = 0;
  private static final int STATE_EXPECT_SECTION_HEADER = 1;
  private static final int STATE_CONSUME_SECTION_DATA = 2;
  private static final int STATE_PACKET_FINISHED = 3;

  private final @Nullable String language;
  private final @C.RoleFlags int roleFlags;

  private @MonotonicNonNull TrackOutput output;
  private boolean packageGoodToGo;
  private long sampleTimeUs;
  private int sectionBytesToRead;
  private int sampleBytesWritten;
  private int sectionType;
  private int state;

  public PgsReader(@Nullable String language, @C.RoleFlags int roleFlags) {
    this.language = language;
    this.roleFlags = roleFlags;
    this.packageGoodToGo = false;
    this.sampleTimeUs = C.TIME_UNSET;
    this.state = STATE_SKIP_SIGNATURE;
  }

  @Override
  public void seek() {
    packageGoodToGo = false;
    sampleTimeUs = C.TIME_UNSET;
    state = STATE_SKIP_SIGNATURE;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
      return;
    }
    packageGoodToGo = true;
    sampleTimeUs = pesTimeUs;
    sampleBytesWritten = 0;
    state = STATE_SKIP_SIGNATURE;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null || !packageGoodToGo) {
      return;
    }
    int bytesAvailable = data.bytesLeft();
    output.sampleData(data, bytesAvailable);
    sampleBytesWritten += bytesAvailable;
    goThrough(data);
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (packageGoodToGo) {
      checkState(sampleTimeUs != C.TIME_UNSET);
      if (state == STATE_PACKET_FINISHED) {
        output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
        sampleTimeUs = C.TIME_UNSET;
      }
      packageGoodToGo = false;
    }
  }

  private void goThrough(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_SKIP_SIGNATURE:
          if (data.bytesLeft() < SIGNATURE_LENGTH) {
            return;
          }
          data.skipBytes(SIGNATURE_LENGTH);
          sectionType = SECTION_TYPE_PTS_DTS;
          sectionBytesToRead = SECTION_PTS_DTS_SIZE;
          state = STATE_CONSUME_SECTION_DATA;
          break;
        case STATE_CONSUME_SECTION_DATA:
          int bytesToSkip = Math.min(sectionBytesToRead, data.bytesLeft());
          data.skipBytes(bytesToSkip);
          sectionBytesToRead -= bytesToSkip;
          if (sectionBytesToRead == 0) {
            if (sectionType == SECTION_TYPE_END) {
              state = STATE_PACKET_FINISHED;
              return;
            }
            state = STATE_EXPECT_SECTION_HEADER;
          }
          return;
        case STATE_EXPECT_SECTION_HEADER:
          if (data.bytesLeft() < 3) {
            return;
          }
          sectionType = data.readUnsignedByte();
          sectionBytesToRead = data.readUnsignedShort();
          if (sectionType == SECTION_TYPE_END) {
            state = STATE_PACKET_FINISHED;
            return;
          }
          if (sectionType != SECTION_TYPE_IDENTIFIER
              && sectionType != SECTION_TYPE_WINDOW_DEF
              && sectionType != SECTION_TYPE_PALETTE
              && sectionType != SECTION_TYPE_BITMAP_PICTURE) {
            packageGoodToGo = false;
            return;
          }
          state = STATE_CONSUME_SECTION_DATA;
          break;
        case STATE_PACKET_FINISHED:
          data.skipBytes(data.bytesLeft());
          return;
        default:
          packageGoodToGo = false;
          return;
      }
    }
  }
}
