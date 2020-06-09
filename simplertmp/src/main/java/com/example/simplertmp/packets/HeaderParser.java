package com.example.simplertmp.packets;

class HeaderParser {
    public byte parseChunkType(byte basicHeaderByte) {
        return (byte) ((0xff & basicHeaderByte) >>> 6);
    }

    public byte parseChunkStreamId(byte basicHeaderByte) {
        return (byte) (basicHeaderByte & 0x3F); // 6 least significant bits define chunk stream ID
    }
}
