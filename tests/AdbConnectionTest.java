package com.skipstery.projectorcalibrator;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AdbConnectionTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    testFraming();
    testAdbKeyAndMincryptEncoding();
    testLegacyAuthHandshakes();
    System.out.println("AdbConnection and AdbKey checks passed");
  }

  private static void testFraming() throws Exception {
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
  }

  private static void testAdbKeyAndMincryptEncoding() throws Exception {
    File tempDir = Files.createTempDirectory("adbkey-test-").toFile();
    try {
      File keyFile = new File(tempDir, "adbkey");
      check(!AdbKey.exists(keyFile), "Key file does not exist initially");

      AdbKey generated = AdbKey.loadOrGenerate(keyFile);
      check(AdbKey.exists(keyFile), "loadOrGenerate persists key file");
      check(generated.privateKey instanceof RSAPrivateCrtKey, "Generated key is RSA CRT key");
      check(generated.certificate != null, "Generated key exposes X.509 certificate for TLS");
      check(generated.publicKey != null, "Generated key exposes legacy publicKey payload");

      AdbKey reloaded = AdbKey.loadOrGenerate(keyFile);
      RSAPrivateCrtKey rsa1 = (RSAPrivateCrtKey) generated.privateKey;
      RSAPrivateCrtKey rsa2 = (RSAPrivateCrtKey) reloaded.privateKey;
      check(rsa1.getModulus().equals(rsa2.getModulus()), "loadOrGenerate reuses persisted key");
      check(Arrays.equals(generated.publicKey, reloaded.publicKey), "Public key bytes match");

      // Check "<base64> <identity>\0" formatting
      byte[] formatted = generated.publicKey;
      check(formatted[formatted.length - 1] == 0, "AUTH_RSAPUBLICKEY payload is NUL-terminated");
      String text = new String(formatted, 0, formatted.length - 1, StandardCharsets.US_ASCII);
      int space = text.indexOf(' ');
      check(space > 0, "AUTH_RSAPUBLICKEY contains space before identity");
      String b64 = text.substring(0, space);
      String identity = text.substring(space + 1);
      check(identity.equals(AdbKey.DEFAULT_IDENTITY), "Identity matches expected value");

      byte[] struct = Base64.getDecoder().decode(b64);
      check(struct.length == 524, "mincrypt RSAPublicKey struct is 524 bytes");
      check(
          Arrays.equals(
              struct, AdbKey.mincryptPublicKey(rsa1.getModulus(), rsa1.getPublicExponent())),
          "Base64 decodes to exact mincryptPublicKey bytes");

      ByteBuffer buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
      int len = buf.getInt();
      int n0inv = buf.getInt();
      byte[] nLittle = new byte[256];
      buf.get(nLittle);
      byte[] rrLittle = new byte[256];
      buf.get(rrLittle);
      int exponent = buf.getInt();
      check(!buf.hasRemaining(), "All 524 bytes accounted for");

      check(len == 64, "len is 64 uint32_t words");
      check(exponent == rsa1.getPublicExponent().intValue(), "exponent matches RSA public exponent");

      // Verify n[0] * n0inv == -1 (mod 2^32)
      int n0 = ByteBuffer.wrap(nLittle, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      check(n0 * n0inv == -1, "n0 * n0inv == -1 mod 2^32");

      // Reconstruct modulus n from little-endian 256 bytes
      BigInteger decodedN = fromLittleEndianUnsigned(nLittle);
      check(decodedN.equals(rsa1.getModulus()), "Little-endian n[64] matches RSA modulus");

      // Reconstruct rr = (2^2048)^2 mod n from little-endian 256 bytes
      BigInteger decodedRr = fromLittleEndianUnsigned(rrLittle);
      BigInteger expectedRr = BigInteger.ONE.shiftLeft(4096).mod(rsa1.getModulus());
      check(decodedRr.equals(expectedRr), "Little-endian rr[64] equals R^2 mod N");

      AdbKey.delete(keyFile);
      check(!AdbKey.exists(keyFile), "AdbKey.delete removes the key file");
    } finally {
      for (File f : tempDir.listFiles()) f.delete();
      tempDir.delete();
    }
  }

  private static BigInteger fromLittleEndianUnsigned(byte[] little) {
    byte[] big = new byte[little.length + 1];
    for (int i = 0; i < little.length; i++) {
      big[big.length - 1 - i] = little[i];
    }
    return new BigInteger(big);
  }

  private static PublicKey decodeMincryptToPublicKey(byte[] payload) throws Exception {
    check(payload.length > 1 && payload[payload.length - 1] == 0, "Payload ends with NUL");
    String str = new String(payload, 0, payload.length - 1, StandardCharsets.US_ASCII);
    String b64 = str.substring(0, str.indexOf(' '));
    byte[] struct = Base64.getDecoder().decode(b64);
    check(struct.length == 524, "524-byte mincrypt struct");
    ByteBuffer buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
    check(buf.getInt() == 64, "len == 64");
    buf.getInt(); // n0inv
    byte[] nLittle = new byte[256];
    buf.get(nLittle);
    buf.position(buf.position() + 256); // skip rr
    int exponent = buf.getInt();
    BigInteger modulus = fromLittleEndianUnsigned(nLittle);
    return KeyFactory.getInstance("RSA")
        .generatePublic(new RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent & 0xffffffffL)));
  }

  private static boolean verifyTokenSignature(PublicKey publicKey, byte[] token, byte[] signature)
      throws Exception {
    byte[] digestInfo = new byte[AdbConnection.SHA1_DIGEST_INFO.length + token.length];
    System.arraycopy(
        AdbConnection.SHA1_DIGEST_INFO, 0, digestInfo, 0, AdbConnection.SHA1_DIGEST_INFO.length);
    System.arraycopy(token, 0, digestInfo, AdbConnection.SHA1_DIGEST_INFO.length, token.length);
    Signature verifier = Signature.getInstance("NONEwithRSA");
    verifier.initVerify(publicKey);
    verifier.update(digestInfo);
    return verifier.verify(signature);
  }

  private static void testLegacyAuthHandshakes() throws Exception {
    AdbKey key = AdbKey.generate();
    byte[] token1 = new byte[20];
    byte[] token2 = new byte[20];
    for (int i = 0; i < 20; i++) {
      token1[i] = (byte) (i + 1);
      token2[i] = (byte) (100 - i);
    }

    // 1. Already-authorized key: CNXN -> AUTH_TOKEN -> AUTH_SIGNATURE -> CNXN -> shell
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      AtomicReference<Throwable> serverError = new AtomicReference<>();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket client = server.accept()) {
                  InputStream in = client.getInputStream();
                  OutputStream out = client.getOutputStream();
                  AdbConnection.Message cnxn = AdbConnection.read(in);
                  check(cnxn.command == AdbConnection.CNXN, "Client starts with CNXN");
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token1));
                  out.flush();

                  AdbConnection.Message auth = AdbConnection.read(in);
                  check(auth.command == AdbConnection.AUTH, "Client replies with AUTH");
                  check(auth.arg0 == AdbConnection.AUTH_SIGNATURE, "First try is AUTH_SIGNATURE");
                  check(
                      verifyTokenSignature(key.certificate.getPublicKey(), token1, auth.data),
                      "Signature validates against SHA-1 DigestInfo + token without double-hashing");

                  out.write(
                      AdbConnection.encode(
                          AdbConnection.CNXN,
                          AdbConnection.VERSION,
                          AdbConnection.MAX_PAYLOAD,
                          "device::\0".getBytes(StandardCharsets.UTF_8)));
                  out.flush();

                  AdbConnection.Message open = AdbConnection.read(in);
                  check(open.command == AdbConnection.OPEN, "Client opens shell stream");
                  check(
                      new String(open.data, StandardCharsets.UTF_8).equals("shell:id\0"),
                      "Shell command matches");
                  out.write(AdbConnection.encode(AdbConnection.OKAY, 42, open.arg0, new byte[0]));
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.WRTE,
                          42,
                          open.arg0,
                          "uid=2000(shell)\n".getBytes(StandardCharsets.UTF_8)));
                  out.flush();
                  AdbConnection.Message okay = AdbConnection.read(in);
                  check(okay.command == AdbConnection.OKAY, "Client acknowledges WRTE");
                  out.write(AdbConnection.encode(AdbConnection.CLSE, 42, open.arg0, new byte[0]));
                  out.flush();
                } catch (Throwable t) {
                  serverError.set(t);
                }
              });
      daemon.start();
      try (AdbConnection adb = AdbConnection.open(port, key, false, 5000)) {
        String output = adb.shell("id");
        check("uid=2000(shell)\n".equals(output), "Shell output round-trips over legacy ADB");
      }
      daemon.join(5000);
      if (serverError.get() != null) throw new AssertionError(serverError.get());
    }

    // 2. New key with sendPublicKey=true:
    // CNXN -> AUTH_TOKEN -> AUTH_SIGNATURE -> second AUTH_TOKEN -> AUTH_RSAPUBLICKEY -> CNXN
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      AtomicReference<Throwable> serverError = new AtomicReference<>();
      AtomicBoolean awaitingCallbackFired = new AtomicBoolean(false);
      Thread daemon =
          new Thread(
              () -> {
                try (Socket client = server.accept()) {
                  InputStream in = client.getInputStream();
                  OutputStream out = client.getOutputStream();
                  AdbConnection.Message cnxn = AdbConnection.read(in);
                  check(cnxn.command == AdbConnection.CNXN, "Client starts with CNXN");
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token1));
                  out.flush();

                  AdbConnection.Message sig = AdbConnection.read(in);
                  check(
                      sig.command == AdbConnection.AUTH
                          && sig.arg0 == AdbConnection.AUTH_SIGNATURE,
                      "Client sends AUTH_SIGNATURE first");
                  // Pretend key is unknown: send a second AUTH_TOKEN
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token2));
                  out.flush();

                  AdbConnection.Message pub = AdbConnection.read(in);
                  check(
                      pub.command == AdbConnection.AUTH
                          && pub.arg0 == AdbConnection.AUTH_RSAPUBLICKEY,
                      "Client sends AUTH_RSAPUBLICKEY after second AUTH_TOKEN");
                  PublicKey decodedPub = decodeMincryptToPublicKey(pub.data);
                  check(
                      verifyTokenSignature(decodedPub, token1, sig.data),
                      "Sent mincrypt public key verifies the original AUTH_SIGNATURE");

                  // User clicks "Allow" on TV dialog -> adbd sends CNXN
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.CNXN,
                          AdbConnection.VERSION,
                          AdbConnection.MAX_PAYLOAD,
                          "device::\0".getBytes(StandardCharsets.UTF_8)));
                  out.flush();

                  AdbConnection.Message open = AdbConnection.read(in);
                  check(open.command == AdbConnection.OPEN, "Shell opened after interactive allow");
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.WRTE,
                          7,
                          open.arg0,
                          "READY\n".getBytes(StandardCharsets.UTF_8)));
                  out.flush();
                  AdbConnection.read(in); // OKAY
                  out.write(AdbConnection.encode(AdbConnection.CLSE, 7, open.arg0, new byte[0]));
                  out.flush();
                } catch (Throwable t) {
                  serverError.set(t);
                }
              });
      daemon.start();
      AtomicReference<Closeable> cancelHandle = new AtomicReference<>();
      try (AdbConnection adb =
          AdbConnection.open(
              port,
              key,
              true,
              5000,
              5000,
              () -> awaitingCallbackFired.set(true),
              cancelHandle)) {
        check(awaitingCallbackFired.get(), "onAwaitingAuth callback fired when prompted");
        check("READY\n".equals(adb.shell("echo READY")), "Shell runs after AUTH_RSAPUBLICKEY");
      }
      check(cancelHandle.get() == null, "cancelHandle is cleared after open completes");
      daemon.join(5000);
      if (serverError.get() != null) throw new AssertionError(serverError.get());
    }

    // 3. Unknown key with sendPublicKey=false (background job): does not send AUTH_RSAPUBLICKEY
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      Thread daemon =
          new Thread(
              () -> {
                try (Socket client = server.accept()) {
                  InputStream in = client.getInputStream();
                  OutputStream out = client.getOutputStream();
                  AdbConnection.read(in); // CNXN
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token1));
                  out.flush();
                  AdbConnection.read(in); // AUTH_SIGNATURE
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token2));
                  out.flush();
                } catch (Throwable ignored) {
                }
              });
      daemon.start();
      boolean caughtUnauthorized = false;
      try (AdbConnection ignored = AdbConnection.open(port, key, false, 5000)) {
        throw new AssertionError("Expected UnauthorizedException when sendPublicKey is false");
      } catch (AdbConnection.UnauthorizedException expected) {
        caughtUnauthorized = true;
        check(!expected.prompted, "UnauthorizedException.prompted is false when not prompting");
      }
      check(caughtUnauthorized, "Background mode throws UnauthorizedException without prompting");
      daemon.join(5000);
    }

    // 4. Timeout waiting for user to approve Allow dialog: throws UnauthorizedException(prompted=true)
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int port = server.getLocalPort();
      CountDownLatch done = new CountDownLatch(1);
      Thread daemon =
          new Thread(
              () -> {
                try (Socket client = server.accept()) {
                  InputStream in = client.getInputStream();
                  OutputStream out = client.getOutputStream();
                  AdbConnection.read(in); // CNXN
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token1));
                  out.flush();
                  AdbConnection.read(in); // AUTH_SIGNATURE
                  out.write(
                      AdbConnection.encode(
                          AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token2));
                  out.flush();
                  AdbConnection.read(in); // AUTH_RSAPUBLICKEY
                  // Hold socket open without replying CNXN to simulate unanswered TV dialog
                  done.await(5, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
              });
      daemon.start();
      boolean caughtTimeout = false;
      try (AdbConnection ignored =
          AdbConnection.open(port, key, true, 2000, 250, null, null)) {
        throw new AssertionError("Expected UnauthorizedException on dialog timeout");
      } catch (AdbConnection.UnauthorizedException expected) {
        caughtTimeout = true;
        check(expected.prompted, "UnauthorizedException.prompted is true after AUTH_RSAPUBLICKEY");
      } finally {
        done.countDown();
      }
      check(caughtTimeout, "Dialog timeout maps to UnauthorizedException(prompted=true)");
      daemon.join(5000);
    }

    // 5. Closed port: throws PortClosedException
    int closedPort;
    try (ServerSocket temp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      closedPort = temp.getLocalPort();
    }
    boolean caughtClosed = false;
    try (AdbConnection ignored = AdbConnection.open(closedPort, key, false, 1000)) {
      throw new AssertionError("Expected PortClosedException on closed port");
    } catch (AdbConnection.PortClosedException expected) {
      caughtClosed = true;
    }
    check(caughtClosed, "Closed TCP port throws PortClosedException");
  }
}
