package com.meonggo.backend.photo.image;

public record NormalizedPhoto(byte[] bytes, int width, int height, String checksum) {
    public NormalizedPhoto {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "NormalizedPhoto[redacted]";
    }
}
