package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DownloadProgressDeadlineTest {
    @Test
    void steadyProgressCanExceedTenMinutesButLimitsAndCancellationRemain() throws Exception {
        var now = new AtomicLong();
        var input = new ByteArrayInputStream(new byte[20]) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                now.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
                return super.read(b, off, Math.min(1, len));
            }
        };
        var output = new ByteArrayOutputStream();
        var progress = new AtomicLong();
        ManagedTools.transfer(input, output, 20, new ProcessRunner.Token(), progress::set, now::get);
        assertEquals(20, output.size());
        assertEquals(20, progress.get());
        assertThrows(IOException.class, () -> ManagedTools.transfer(new ByteArrayInputStream(new byte[21]), new ByteArrayOutputStream(), 20, new ProcessRunner.Token(), n -> {
        }, System::nanoTime));
        var token = new ProcessRunner.Token();
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> ManagedTools.transfer(new ByteArrayInputStream(new byte[1]), new ByteArrayOutputStream(), 20, token, n -> {
        }, System::nanoTime));
    }

    @Test
    void endlessEmptyReadsCannotKeepTheOperationAlive() {
        var now = new AtomicLong();
        InputStream empty = new InputStream() {
            public int read() {
                return 0;
            }

            public int read(byte[] b, int o, int n) {
                now.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
                return 0;
            }
        };
        assertThrows(IOException.class, () -> ManagedTools.transfer(empty, new ByteArrayOutputStream(), 20, new ProcessRunner.Token(), n -> {
        }, now::get));
    }
}
