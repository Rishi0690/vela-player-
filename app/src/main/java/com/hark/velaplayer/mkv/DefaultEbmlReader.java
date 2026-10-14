/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Vela Player: copy of androidx.media3.extractor.mkv.DefaultEbmlReader (media3 1.3.1): package changed and
// checker-framework nullness annotations removed (not on the app classpath); code unchanged except v2.19.3 strict mode, size sanity checks and seek-skip of big elements (VELA). Needed
// because it is package-private and used by VelaMatroskaExtractor.
package com.hark.velaplayer.mkv;

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.extractor.ExtractorInput;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;

/** Default implementation of {@link EbmlReader}. */
/* package */ final class DefaultEbmlReader implements EbmlReader {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({ELEMENT_STATE_READ_ID, ELEMENT_STATE_READ_CONTENT_SIZE, ELEMENT_STATE_READ_CONTENT})
  private @interface ElementState {}

  private static final int ELEMENT_STATE_READ_ID = 0;
  private static final int ELEMENT_STATE_READ_CONTENT_SIZE = 1;
  private static final int ELEMENT_STATE_READ_CONTENT = 2;

  private static final int MAX_ID_BYTES = 4;
  private static final int MAX_LENGTH_BYTES = 8;

  private static final int MAX_INTEGER_ELEMENT_SIZE_BYTES = 8;
  private static final int VALID_FLOAT32_ELEMENT_SIZE_BYTES = 4;
  private static final int VALID_FLOAT64_ELEMENT_SIZE_BYTES = 8;

  private final byte[] scratch;
  private final ArrayDeque<MasterElement> masterElementsStack;
  private final VarintReader varintReader;

  private EbmlProcessor processor;
  private @ElementState int elementState;
  private int elementId;
  private long elementContentSize;
  private boolean strict; // VELA
  private boolean seekSkipAllowed; // VELA
  private static final String TAG = "VelaEbmlReader"; // VELA
  /** VELA: unknown/Void elements bigger than this are skipped by a seek instead of being read through. */
  /* package */ static final long SEEK_SKIP_THRESHOLD = 256 * 1024;
  /** VELA: the value Media3 sees for the canonical 8-byte unknown size; shorter unknown sizes are mapped to it. */
  private static final long UNKNOWN_SIZE = (1L << 56) - 1;
  private static final long UNKNOWN_END = 1L << 55; // ends beyond this come from an unknown size
  private long pendingSeekPosition = C.INDEX_UNSET; // VELA

  public DefaultEbmlReader() {
    scratch = new byte[8];
    masterElementsStack = new ArrayDeque<>();
    varintReader = new VarintReader();
  }

  @Override
  public void init(EbmlProcessor processor) {
    this.processor = processor;
  }

  @Override
  public void setStrict(boolean strict) { // VELA
    this.strict = strict;
  }

  @Override
  public void setSeekSkipAllowed(boolean allowed) { // VELA
    seekSkipAllowed = allowed;
  }

  @Override
  public long consumePendingSeekPosition() { // VELA
    long position = pendingSeekPosition;
    pendingSeekPosition = C.INDEX_UNSET;
    return position;
  }

  /**
   * VELA: a non-master size that can't be real: past the end of the input (when known), or too big for the int
   * based binary/string/integer handling (only skipped unknown elements may legitimately exceed 2 GiB).
   */
  private static boolean isImplausibleSize(ExtractorInput input, int type, long size) {
    if (size < 0) {
      return true;
    }
    long length = input.getLength();
    if (length != C.LENGTH_UNSET && input.getPosition() + size > length) {
      return true;
    }
    return type != EbmlProcessor.ELEMENT_TYPE_UNKNOWN && size > Integer.MAX_VALUE;
  }

  @Override
  public void reset() {
    pendingSeekPosition = C.INDEX_UNSET;
    elementState = ELEMENT_STATE_READ_ID;
    masterElementsStack.clear();
    varintReader.reset();
  }

  @Override
  public boolean read(ExtractorInput input) throws IOException {
    Assertions.checkStateNotNull(processor);
    while (true) {
      MasterElement head = masterElementsStack.peek();
      if (head != null && input.getPosition() >= head.elementEndPosition) {
        processor.endMasterElement(masterElementsStack.pop().elementId);
        return true;
      }

      if (elementState == ELEMENT_STATE_READ_ID) {
        long result = varintReader.readUnsignedVarint(input, true, false, MAX_ID_BYTES);
        if (result == C.RESULT_MAX_LENGTH_EXCEEDED) {
          if (strict) { // VELA
            throw ParserException.createForMalformedContainer(
                "Invalid element ID at " + input.getPosition(), /* cause= */ null);
          }
          result = maybeResyncToNextLevel1Element(input);
        }
        if (result == C.RESULT_END_OF_INPUT) {
          return false;
        }
        // Element IDs are at most 4 bytes, so we can cast to integers.
        elementId = (int) result;
        elementState = ELEMENT_STATE_READ_CONTENT_SIZE;
      }

      if (elementState == ELEMENT_STATE_READ_CONTENT_SIZE) {
        elementContentSize = varintReader.readUnsignedVarint(input, false, true, MAX_LENGTH_BYTES);
        elementState = ELEMENT_STATE_READ_CONTENT;
        // VELA: EBML "unknown size" is ALL value bits set at ANY width (e.g. 0xFF, 0x7FFF, 0x1FFFFFFF), not only the
        // 8-byte 0x01FFFFFFFFFFFFFF Media3 happens to handle (as a practically infinite size). A shorter all-ones
        // size was taken literally (a Cluster of "127 bytes"), mis-syncing the parse into garbage elements.
        int sizeLength = varintReader.getLastLength();
        if (sizeLength < MAX_LENGTH_BYTES && elementContentSize == (1L << (7 * sizeLength)) - 1) {
          elementContentSize = UNKNOWN_SIZE;
        }
        // VELA (v2.19.3): sizes are never trusted blindly. A non-master element that extends past the end of
        // the input, or is too big for the int APIs below, is garbage (a stale index pointer, a corrupt or
        // mis-synced region): strict mode (reading a seek index) fails now; otherwise resync to the next level 1
        // element (Cluster, Cues, ...) instead of skipping/reading with a wrapped (int) size. Plain Media3 1.3.1
        // did skipFully((int) size): a size in (2^31, 2^32) became negative and crashed DefaultExtractorInput
        // with ArrayIndexOutOfBoundsException (UnexpectedLoaderException → ERROR_CODE_IO_UNSPECIFIED).
        // Masters are unchanged (a truncated Cluster is read until the input ends).
        if (strict
            && input.getLength() != C.LENGTH_UNSET
            && input.getPosition() + elementContentSize > input.getLength()) {
          throw ParserException.createForMalformedContainer(
              "Element 0x" + Integer.toHexString(elementId) + " of size " + elementContentSize + " at "
                  + input.getPosition() + " extends past the end (" + input.getLength() + ")",
              /* cause= */ null);
        }
        int checkType = processor.getElementType(elementId);
        if (elementContentSize < 0 // invalid size varint (RESULT_MAX_LENGTH_EXCEEDED)
            || (checkType != EbmlProcessor.ELEMENT_TYPE_MASTER
                && isImplausibleSize(input, checkType, elementContentSize))) {
          String what = "Element 0x" + Integer.toHexString(elementId) + " of size " + elementContentSize
              + " at " + input.getPosition() + " (input length " + input.getLength() + ")";
          if (strict) {
            throw ParserException.createForMalformedContainer(what, /* cause= */ null);
          }
          Log.w(TAG, what + " is implausible: resyncing to the next level 1 element");
          elementId = (int) maybeResyncToNextLevel1Element(input);
          elementState = ELEMENT_STATE_READ_CONTENT_SIZE;
          continue;
        }
      }

      @EbmlProcessor.ElementType int type = processor.getElementType(elementId);
      switch (type) {
        case EbmlProcessor.ELEMENT_TYPE_MASTER:
          long elementContentPosition = input.getPosition();
          long elementEndPosition = elementContentPosition + elementContentSize;
          // VELA: a child (corrupt or unknown size) can't extend past its known-size parent: clamp it so the
          // parent still ends where it says and the parent's next sibling is read in sync.
          if (head != null
              && head.elementEndPosition < UNKNOWN_END
              && elementEndPosition > head.elementEndPosition) {
            if (elementContentSize != UNKNOWN_SIZE) {
              Log.w(TAG, "Master 0x" + Integer.toHexString(elementId) + " extends past its parent: clamped");
            }
            elementEndPosition = head.elementEndPosition;
            elementContentSize = elementEndPosition - elementContentPosition;
          }
          masterElementsStack.push(new MasterElement(elementId, elementEndPosition));
          processor.startMasterElement(elementId, elementContentPosition, elementContentSize);
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT:
          if (elementContentSize > MAX_INTEGER_ELEMENT_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer(
                "Invalid integer size: " + elementContentSize, /* cause= */ null);
          }
          processor.integerElement(elementId, readInteger(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_FLOAT:
          if (elementContentSize != VALID_FLOAT32_ELEMENT_SIZE_BYTES
              && elementContentSize != VALID_FLOAT64_ELEMENT_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer(
                "Invalid float size: " + elementContentSize, /* cause= */ null);
          }
          processor.floatElement(elementId, readFloat(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_STRING:
          if (elementContentSize > Integer.MAX_VALUE) {
            throw ParserException.createForMalformedContainer(
                "String element size: " + elementContentSize, /* cause= */ null);
          }
          processor.stringElement(elementId, readString(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_BINARY:
          processor.binaryElement(elementId, (int) elementContentSize, input);
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_UNKNOWN:
          // VELA: never (int)-cast the size. A big element is skipped by a seek (the extractor returns
          // RESULT_SEEK), like Mp4Extractor does for big atoms: always for local files, and for > 2 GiB when the
          // input length is known (range requests work). Otherwise it is read through in int-sized chunks, as
          // before (an unseekable stream can't jump).
          elementState = ELEMENT_STATE_READ_ID;
          boolean huge = elementContentSize > Integer.MAX_VALUE;
          if ((seekSkipAllowed && elementContentSize > SEEK_SKIP_THRESHOLD)
              || (huge && input.getLength() != C.LENGTH_UNSET)) {
            pendingSeekPosition = input.getPosition() + elementContentSize;
            return true;
          }
          for (long left = elementContentSize; left > 0; ) {
            int chunk = (int) Math.min(left, 1 << 20);
            input.skipFully(chunk);
            left -= chunk;
          }
          break;
        default:
          throw ParserException.createForMalformedContainer(
              "Invalid element type " + type, /* cause= */ null);
      }
    }
  }

  /**
   * Does a byte by byte search to try and find the next level 1 element. This method is called if
   * some invalid data is encountered in the parser.
   *
   * @param input The {@link ExtractorInput} from which data has to be read.
   * @return id of the next level 1 element that has been found.
   * @throws EOFException If the end of input was encountered when searching for the next level 1
   *     element.
   * @throws IOException If an error occurs reading from the input.
   */
  private long maybeResyncToNextLevel1Element(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    while (true) {
      input.peekFully(scratch, 0, MAX_ID_BYTES);
      int varintLength = VarintReader.parseUnsignedVarintLength(scratch[0]);
      if (varintLength != C.LENGTH_UNSET && varintLength <= MAX_ID_BYTES) {
        int potentialId = (int) VarintReader.assembleVarint(scratch, varintLength, false);
        if (processor.isLevel1Element(potentialId)) {
          input.skipFully(varintLength);
          return potentialId;
        }
      }
      input.skipFully(1);
    }
  }

  /**
   * Reads and returns an integer of length {@code byteLength} from the {@link ExtractorInput}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the integer being read.
   * @return The read integer value.
   * @throws IOException If an error occurs reading from the input.
   */
  private long readInteger(ExtractorInput input, int byteLength) throws IOException {
    input.readFully(scratch, 0, byteLength);
    long value = 0;
    for (int i = 0; i < byteLength; i++) {
      value = (value << 8) | (scratch[i] & 0xFF);
    }
    return value;
  }

  /**
   * Reads and returns a float of length {@code byteLength} from the {@link ExtractorInput}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the float being read.
   * @return The read float value.
   * @throws IOException If an error occurs reading from the input.
   */
  private double readFloat(ExtractorInput input, int byteLength) throws IOException {
    long integerValue = readInteger(input, byteLength);
    double floatValue;
    if (byteLength == VALID_FLOAT32_ELEMENT_SIZE_BYTES) {
      floatValue = Float.intBitsToFloat((int) integerValue);
    } else {
      floatValue = Double.longBitsToDouble(integerValue);
    }
    return floatValue;
  }

  /**
   * Reads a string of length {@code byteLength} from the {@link ExtractorInput}. Zero padding is
   * removed, so the returned string may be shorter than {@code byteLength}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the string being read, including zero padding.
   * @return The read string value.
   * @throws IOException If an error occurs reading from the input.
   */
  private static String readString(ExtractorInput input, int byteLength) throws IOException {
    if (byteLength == 0) {
      return "";
    }
    byte[] stringBytes = new byte[byteLength];
    input.readFully(stringBytes, 0, byteLength);
    // Remove zero padding.
    int trimmedLength = byteLength;
    while (trimmedLength > 0 && stringBytes[trimmedLength - 1] == 0) {
      trimmedLength--;
    }
    return new String(stringBytes, 0, trimmedLength);
  }

  /**
   * Used in {@link #masterElementsStack} to track when the current master element ends, so that
   * {@link EbmlProcessor#endMasterElement(int)} can be called.
   */
  private static final class MasterElement {

    private final int elementId;
    private final long elementEndPosition;

    private MasterElement(int elementId, long elementEndPosition) {
      this.elementId = elementId;
      this.elementEndPosition = elementEndPosition;
    }
  }
}
