package com.skipstery.projectorcalibrator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AdbConnectionTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    byte[] payload = "host::\0".getBytes(StandardCharsets.UTF_8);
    byte[] wire =
        AdbConnection.encode(
            AdbConnection.CNXN, AdbConnection.VERSION, AdbConnection.MAX_PAYLOAD, payload);
    check(wire.length == 24 + payload.length, "Header is 24 bytes");
    check(
        new String(wire, 0, 4, StandardCharsets.US_ASCII).equals("CNXN"),
        "Command is little-endian ASCII");
    AdbConnection.Message message = AdbConnection.read(new ByteArrayInputStream(wire));
    check(message.command == AdbConnection.CNXN, "Command round-trips");
    check(message.arg0 == AdbConnection.VERSION, "arg0 round-trips");
    check(message.arg1 == AdbConnection.MAX_PAYLOAD, "arg1 round-trips");
    check(new String(message.data, StandardCharsets.UTF_8).equals("host::\0"), "Payload");
    check(message.name().equals("CNXN"), "Readable command name");

    wire[20] ^= 1;
    boolean rejected = false;
    try {
      AdbConnection.read(new ByteArrayInputStream(wire));
    } catch (IOException e) {
      rejected = true;
    }
    check(rejected, "A bad magic word is rejected");

    byte[] huge = AdbConnection.encode(AdbConnection.WRTE, 1, 1, new byte[0]);
    huge[12] = 0;
    huge[13] = 0;
    huge[14] = 0x20; // 2 MiB, above MAX_PAYLOAD
    rejected = false;
    try {
      AdbConnection.read(new ByteArrayInputStream(huge));
    } catch (IOException e) {
      rejected = true;
    }
    check(rejected, "Oversized payloads are rejected before allocation");
    System.out.println("AdbConnection framing checks passed");
  }
}
