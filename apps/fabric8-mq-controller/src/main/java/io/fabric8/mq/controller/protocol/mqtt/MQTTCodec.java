/*
 *
 *  * Copyright 2005-2015 Red Hat, Inc.
 *  * Red Hat licenses this file to you under the Apache License, version
 *  * 2.0 (the "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  * implied.  See the License for the specific language governing
 *  * permissions and limitations under the License.
 *
 */
package io.fabric8.mq.controller.protocol.mqtt;

import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.DataByteArrayInputStream;
import org.fusesource.mqtt.codec.MQTTFrame;

import java.io.IOException;

public class MQTTCodec {

    private final MQTTFrameSink frameSink;
    private final Buffer scratch = new Buffer(8 * 1024);
    private byte header;
    private int contentLength = -1;
    private FrameParser currentParser;
    private Buffer currentBuffer;
    private final FrameParser contentParser = new FrameParser() {

        private int payLoadRead = 0;

        @Override
        public void parse(DataByteArrayInputStream data, int readSize) throws IOException {
            if (currentBuffer == null) {
                if (contentLength < scratch.length()) {
                    currentBuffer = scratch;
                    currentBuffer.length = contentLength;
                } else {
                    currentBuffer = new Buffer(contentLength);
                }
            }

            int length = Math.min(readSize, contentLength - payLoadRead);
            payLoadRead += data.read(currentBuffer.data, payLoadRead, length);

            if (payLoadRead == contentLength) {
                processCommand();
                currentParser = initializeHeaderParser();
                readSize = readSize - length;
                if (readSize > 0) {
                    currentParser.parse(data, readSize);
                }
            }
        }

        @Override
        public void reset() throws IOException {
            contentLength = -1;
            payLoadRead = 0;
            scratch.reset();
            currentBuffer = null;
        }
    };
    private final FrameParser variableLengthParser = new FrameParser() {

        private byte digit;
        private int multiplier = 1;
        private int length;

        @Override
        public void parse(DataByteArrayInputStream data, int readSize) throws IOException {
            int i = 0;
            while (i++ < readSize) {
                digit = data.readByte();
                length += (digit & 0x7F) * multiplier;
                multiplier <<= 7;
                if ((digit & 0x80) == 0) {
                    if (length == 0) {
                        processCommand();
                        currentParser = initializeHeaderParser();
                    } else {
                        currentParser = initializeContentParser();
                        contentLength = length;
                    }

                    readSize = readSize - i;
                    if (readSize > 0) {
                        currentParser.parse(data, readSize);
                    }
                    return;
                }
            }
        }

        @Override
        public void reset() throws IOException {
            digit = 0;
            multiplier = 1;
            length = 0;
        }
    };
    private final FrameParser headerParser = new FrameParser() {

        @Override
        public void parse(DataByteArrayInputStream data, int readSize) throws IOException {
            while (readSize-- > 0) {
                byte b = data.readByte();
                // skip repeating nulls
                if (b == 0) {
                    continue;
                }

                header = b;

                currentParser = initializeVariableLengthParser();
                if (readSize > 0) {
                    currentParser.parse(data, readSize);
                }
                return;
            }
        }

        @Override
        public void reset() throws IOException {
            header = -1;
            contentLength = -1;
        }
    };

    public MQTTCodec(MQTTFrameSink sink) {
        this.frameSink = sink;
    }

    public MQTTCodec(final MQTTWriteStream writeStream) {
        this.frameSink = new MQTTFrameSink() {

            @Override
            public void onFrame(MQTTFrame mqttFrame) {
                writeStream.consume(mqttFrame);
            }
        };
    }

    //----- Prepare the current frame parser for use -------------------------//

    public void parse(DataByteArrayInputStream input, int readSize) throws Exception {
        if (currentParser == null) {
            currentParser = initializeHeaderParser();
        }

        // Parser stack will run until current incoming data has all been consumed.
        currentParser.parse(input, readSize);
    }

    private void processCommand() throws IOException {

        Buffer frameContents = null;
        if (currentBuffer == scratch) {
            frameContents = scratch.deepCopy();
        } else {
            frameContents = currentBuffer;
            currentBuffer = null;
        }

        MQTTFrame frame = new MQTTFrame(frameContents).header(header);
        frameSink.onFrame(frame);
    }

    private FrameParser initializeHeaderParser() throws IOException {
        headerParser.reset();
        return headerParser;
    }

    //----- Frame parser implementations -------------------------------------//

    private FrameParser initializeVariableLengthParser() throws IOException {
        variableLengthParser.reset();
        return variableLengthParser;
    }

    private FrameParser initializeContentParser() throws IOException {
        contentParser.reset();
        return contentParser;
    }

    /**
     * Sink for newly decoded MQTT Frames.
     */
    public interface MQTTFrameSink {
        void onFrame(MQTTFrame mqttFrame);
    }

    private interface FrameParser {

        void parse(DataByteArrayInputStream data, int readSize) throws IOException;

        void reset() throws IOException;
    }
}
