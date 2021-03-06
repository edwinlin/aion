package org.aion.api.server;

import java.nio.ByteBuffer;

public class ApiUtil {
    public static final int HASH_LEN = 8;
    public static final int HEADER_LEN = 4;
    public static final int RETHEADER_LEN = 3;

    public static byte[] toReturnHeader(int vers, int retCode, byte[] hash) {

        if (hash == null || hash.length != HASH_LEN) {
            return null;
        }

        return ByteBuffer.allocate(HASH_LEN + RETHEADER_LEN)
                .put((byte) vers)
                .put((byte) retCode)
                .put((byte) 1)
                .put(hash, 0, hash.length)
                .array();
    }

    public static byte[] toReturnHeader(int vers, int retCode, byte[] hash, byte[] error) {

        if (hash == null || hash.length != HASH_LEN) {
            return null;
        }

        if (error.length == 0) {
            return ByteBuffer.allocate(HASH_LEN + RETHEADER_LEN + 1)
                    .put((byte) vers)
                    .put((byte) retCode)
                    .put((byte) 1)
                    .put(hash, 0, hash.length)
                    .put((byte) 0)
                    .array();
        } else {
            return ByteBuffer.allocate(HASH_LEN + RETHEADER_LEN + 1 + error.length)
                    .put((byte) vers)
                    .put((byte) retCode)
                    .put((byte) 1)
                    .put(hash, 0, hash.length)
                    .put((byte) error.length)
                    .put(error, 0, error.length)
                    .array();
        }
    }

    public static byte[] toReturnHeader(
            int vers, int retCode, byte[] hash, byte[] error, byte[] result) {

        if (hash == null || result == null || hash.length != HASH_LEN) {
            return null;
        }

        if (error.length == 0) {
            return ByteBuffer.allocate(HASH_LEN + RETHEADER_LEN + 1 + result.length)
                    .put((byte) vers)
                    .put((byte) retCode)
                    .put((byte) 1)
                    .put(hash, 0, hash.length)
                    .put((byte) 0)
                    .put(result, 0, result.length)
                    .array();
        } else {
            return ByteBuffer.allocate(HASH_LEN + RETHEADER_LEN + 1 + error.length + result.length)
                    .put((byte) vers)
                    .put((byte) retCode)
                    .put((byte) 1)
                    .put(hash, 0, hash.length)
                    .put((byte) error.length)
                    .put(error, 0, error.length)
                    .put(result, 0, result.length)
                    .array();
        }
    }

    public static byte[] toReturnHeader(int vers, int retCode) {

        return ByteBuffer.allocate(RETHEADER_LEN)
                .put((byte) vers)
                .put((byte) retCode)
                .put((byte) 0)
                .array();
    }

    public static byte[] combineRetMsg(byte[] header, byte[] body) {
        if (header == null || body == null) {
            return null;
        }

        return ByteBuffer.allocate(header.length + body.length)
                .put(header, 0, header.length)
                .put(body, 0, body.length)
                .array();
    }

    public static byte[] combineRetMsg(byte[] header, byte body) {
        if (header == null) {
            return null;
        }

        return ByteBuffer.allocate(header.length + 1)
                .put(header, 0, header.length)
                .put(body)
                .array();
    }

    public static byte[] getApiMsgHash(byte[] request) {
        if (request == null || request[3] == 0) {
            return null;
        }

        return ByteBuffer.allocate(HASH_LEN).put(request, HEADER_LEN, HASH_LEN).array();
    }

    public static byte[] toReturnEvtHeader(byte vers, byte[] ecb) {
        return ByteBuffer.allocate(RETHEADER_LEN + ecb.length)
                .put(vers)
                .put((byte) 106)
                .put((byte) 0)
                .put(ecb, 0, ecb.length)
                .array();
    }
}
