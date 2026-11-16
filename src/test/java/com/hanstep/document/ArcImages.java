package com.hanstep.document;

import java.security.SecureRandom;

/**
 * 테스트용 등록증 "이미지" 바이트. 업로드 검사는 앞부분(매직 바이트)만 보므로 형식 헤더 + 무작위 바이트로 만든다.
 * 실제 등록증 이미지는 쓰지 않는다. 매번 내용이 달라 sha256 이 겹치지 않는다.
 */
public final class ArcImages {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ArcImages() {
    }

    public static byte[] jpeg(int size) {
        byte[] bytes = random(size);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    public static byte[] png(int size) {
        byte[] bytes = random(size);
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    public static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
