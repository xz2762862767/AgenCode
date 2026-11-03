package com.nebula.aichat.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 简易 ZIP 写入器，支持条目对齐（zipalign 等价）。
 * resources.arsc 需 STORED + 4 字节对齐；.so 需 STORED + 4096 页对齐。
 */
public class ZipWriter {

    private static final int ALIGN_EXTRA_ID = 0xD935;

    private static class Central {
        String name;
        int method;
        long crc;
        long csize;
        long usize;
        long offset;
        int dosTime;
        int dosDate;
    }

    private final OutputStream out;
    private final List<Central> central = new ArrayList<>();
    private long pos = 0;

    public ZipWriter(OutputStream out) {
        this.out = out;
    }

    public long position() {
        return pos;
    }

    public void addImage(String name, byte[] data) throws IOException {
        add(name, data, Deflater.DEFAULT_COMPRESSION, 0);
    }

    /** 普通条目：compressionLevel 0 表示 STORED */
    public void add(String name, byte[] data, int compressionLevel, int align) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        long crc = crc32.getValue();

        byte[] payload;
        int method;
        if (compressionLevel == 0) {
            payload = data;
            method = 0;
        } else {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            Deflater def = new Deflater(compressionLevel, true);
            DeflaterOutputStream dos = new DeflaterOutputStream(bos, def, 8192);
            dos.write(data);
            dos.finish();
            dos.close();
            def.end();
            payload = bos.toByteArray();
            method = 8;
            if (payload.length >= data.length) {
                payload = data;
                method = 0;
            }
        }
        writeEntry(name, payload, method, crc, data.length, align);
    }

    /** 大文件流式 STORED 写入（crc/大小由调用方从原 ZipEntry 提供） */
    public void addStreamed(String name, InputStream in, long size, long crc, int align)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int extraLen = computeExtraLen(nameBytes.length, align);
        int flags = 0x800; // UTF-8

        long offset = pos;
        int dosTime = dosTime();
        int dosDate = dosDate();

        writeLe32(0x04034b50);
        writeLe16(20);
        writeLe16(flags);
        writeLe16(0);
        writeLe16(dosTime);
        writeLe16(dosDate);
        writeLe32(crc);
        writeLe32(size);
        writeLe32(size);
        writeLe16(nameBytes.length);
        writeLe16(extraLen);
        out.write(nameBytes);
        pos += nameBytes.length;
        writePadding(extraLen);

        byte[] buf = new byte[65536];
        long written = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            written += n;
        }
        pos += written;
        if (written != size) {
            throw new IOException("条目大小不符: " + name + " 期望 " + size + " 实际 " + written);
        }

        Central c = new Central();
        c.name = name;
        c.method = 0;
        c.crc = crc;
        c.csize = size;
        c.usize = size;
        c.offset = offset;
        c.dosTime = dosTime;
        c.dosDate = dosDate;
        central.add(c);
    }

    private void writeEntry(String name, byte[] payload, int method, long crc, long usize, int align)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int extraLen = computeExtraLen(nameBytes.length, align);
        int flags = 0x800;

        long offset = pos;
        int dosTime = dosTime();
        int dosDate = dosDate();

        writeLe32(0x04034b50);
        writeLe16(20);
        writeLe16(flags);
        writeLe16(method);
        writeLe16(dosTime);
        writeLe16(dosDate);
        writeLe32(crc);
        writeLe32(payload.length);
        writeLe32(usize);
        writeLe16(nameBytes.length);
        writeLe16(extraLen);
        out.write(nameBytes);
        pos += nameBytes.length;
        writePadding(extraLen);
        out.write(payload);
        pos += payload.length;

        Central c = new Central();
        c.name = name;
        c.method = method;
        c.crc = crc;
        c.csize = payload.length;
        c.usize = usize;
        c.offset = offset;
        c.dosTime = dosTime;
        c.dosDate = dosDate;
        central.add(c);
    }

    /** 计算让 data 起始偏移对齐所需的 extra 长度 */
    private int computeExtraLen(int nameLen, int align) {
        if (align <= 1) return 0;
        long base = pos + 30 + nameLen;
        int rem = (int) (base % align);
        if (rem == 0) return 0;
        int need = align - rem;
        // extra field 结构要求 >= 4 字节（id 2 + len 2）；不足时补一整个 align
        while (need < 4) need += align;
        return need;
    }

    private void writePadding(int extraLen) throws IOException {
        if (extraLen <= 0) return;
        writeLe16(ALIGN_EXTRA_ID);
        writeLe16(extraLen - 4);
        for (int i = 0; i < extraLen - 4; i++) out.write(0);
        pos += extraLen - 4;
    }

    public void finish() throws IOException {
        long cdStart = pos;
        for (Central c : central) {
            byte[] nameBytes = c.name.getBytes(StandardCharsets.UTF_8);
            writeLe32(0x02014b50);
            writeLe16(20);          // version made by
            writeLe16(20);          // version needed
            writeLe16(0x800);       // flags: UTF-8
            writeLe16(c.method);
            writeLe16(c.dosTime);
            writeLe16(c.dosDate);
            writeLe32(c.crc);
            writeLe32(c.csize);
            writeLe32(c.usize);
            writeLe16(nameBytes.length);
            writeLe16(0);           // extra
            writeLe16(0);           // comment
            writeLe16(0);           // disk start
            writeLe16(0);           // internal attr
            writeLe32(0);           // external attr
            writeLe32(c.offset);
            out.write(nameBytes);
            pos += nameBytes.length;
        }
        long cdSize = pos - cdStart;

        writeLe32(0x06054b50);
        writeLe16(0);
        writeLe16(0);
        writeLe16(central.size());
        writeLe16(central.size());
        writeLe32(cdSize);
        writeLe32(cdStart);
        writeLe16(0);
        out.flush();
    }

    private void writeLe16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        pos += 2;
    }

    private void writeLe32(long v) throws IOException {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
        pos += 4;
    }

    private static int dosTime() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return (c.get(java.util.Calendar.HOUR_OF_DAY) << 11)
                | (c.get(java.util.Calendar.MINUTE) << 5)
                | (c.get(java.util.Calendar.SECOND) / 2);
    }

    private static int dosDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return ((c.get(java.util.Calendar.YEAR) - 1980) << 9)
                | ((c.get(java.util.Calendar.MONTH) + 1) << 5)
                | c.get(java.util.Calendar.DAY_OF_MONTH);
    }
}
