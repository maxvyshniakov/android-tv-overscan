package com.skipstery.projectorcalibrator;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal client for the device's own adbd over either legacy RSA AUTH (port 5555) or TLS
 * (Wireless debugging), enough to run one shell command on the loopback interface.
 */
final class AdbConnection implements AutoCloseable, Closeable {
  static final int CNXN = 0x4e584e43, STLS = 0x534c5453, OPEN = 0x4e45504f;
  static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;
  static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
  static final int VERSION = 0x01000001, STLS_VERSION = 0x01000000, MAX_PAYLOAD = 1 << 20;
  static final int TOKEN_BYTES = 20;
  static final byte[] SHA1_DIGEST_INFO = {
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
  };
  private static final int LOCAL_ID = 1, MAX_OUTPUT = 64 * 1024;

  /** Thrown when the target ADB port is closed or refuses the TCP connection. */
  static final class PortClosedException extends IOException {
    PortClosedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Thrown when adbd does not recognize the client's RSA signature and either {@code
   * AUTH_RSAPUBLICKEY} was suppressed or the user did not approve the authorization dialog.
   */
  static final class UnauthorizedException extends IOException {
    final boolean prompted;

    UnauthorizedException(boolean prompted, String message) {
      super(message);
      this.prompted = prompted;
    }

    UnauthorizedException(boolean prompted, String message, Throwable cause) {
      super(message, cause);
      this.prompted = prompted;
    }
  }

  private final Socket raw;
  private SSLSocket tls;
  private InputStream in;
  private OutputStream out;

  private AdbConnection(Socket raw) {
    this.raw = raw;
  }

  static AdbConnection open(
      int port, PrivateKey key, X509Certificate certificate, int timeoutMs) throws Exception {
    return open(port, key, certificate, null, false, timeoutMs, timeoutMs, null, null);
  }

  static AdbConnection open(int port, AdbKey key, boolean sendPublicKey, int timeoutMs)
      throws Exception {
    return open(
        port,
        key.privateKey,
        key.certificate,
        key.publicKey,
        sendPublicKey,
        timeoutMs,
        timeoutMs,
        null,
        null);
  }

  static AdbConnection open(
      int port,
      AdbKey key,
      boolean sendPublicKey,
      int timeoutMs,
      int authTimeoutMs,
      Runnable onAwaitingAuth,
      AtomicReference<Closeable> cancelHandle)
      throws Exception {
    return open(
        port,
        key.privateKey,
        key.certificate,
        key.publicKey,
        sendPublicKey,
        timeoutMs,
        authTimeoutMs,
        onAwaitingAuth,
        cancelHandle);
  }

  static AdbConnection open(
      int port,
      PrivateKey key,
      X509Certificate certificate,
      byte[] publicKey,
      boolean sendPublicKey,
      int timeoutMs,
      int authTimeoutMs,
      Runnable onAwaitingAuth,
      AtomicReference<Closeable> cancelHandle)
      throws Exception {
    Socket raw = new Socket();
    AdbConnection connection = new AdbConnection(raw);
    if (cancelHandle != null) cancelHandle.set(connection);
    try {
      try {
        raw.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
      } catch (IOException error) {
        throw new PortClosedException("ADB port " + port + " is not listening", error);
      }
      raw.setSoTimeout(timeoutMs);
      connection.handshake(
          key, certificate, publicKey, sendPublicKey, timeoutMs, authTimeoutMs, onAwaitingAuth);
      return connection;
    } catch (Exception error) {
      connection.close();
      throw error;
    } finally {
      if (cancelHandle != null) cancelHandle.compareAndSet(connection, null);
    }
  }

  private void handshake(
      PrivateKey key,
      X509Certificate certificate,
      byte[] publicKey,
      boolean sendPublicKey,
      int timeoutMs,
      int authTimeoutMs,
      Runnable onAwaitingAuth)
      throws Exception {
    InputStream rawIn = raw.getInputStream();
    OutputStream rawOut = raw.getOutputStream();
    write(rawOut, CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
    Message reply = read(rawIn);
    if (reply.command == AUTH) {
      authenticateLegacy(
          reply,
          rawIn,
          rawOut,
          key,
          publicKey,
          sendPublicKey,
          timeoutMs,
          authTimeoutMs,
          onAwaitingAuth);
      in = rawIn;
      out = rawOut;
      return;
    }
    if (reply.command == CNXN) {
      in = rawIn;
      out = rawOut;
      return;
    }
    if (reply.command != STLS) throw new IOException("Unexpected ADB reply " + reply.name());
    if (certificate == null)
      throw new IOException("ADB requested TLS, but no client certificate was provided");
    write(rawOut, STLS, STLS_VERSION, 0, new byte[0]);
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(
        new KeyManager[] {keys(key, certificate)}, new TrustManager[] {new LoopbackTrust()}, null);
    tls =
        (SSLSocket) context.getSocketFactory().createSocket(raw, "127.0.0.1", raw.getPort(), true);
    tls.setEnabledProtocols(new String[] {"TLSv1.3"});
    tls.startHandshake();
    in = tls.getInputStream();
    out = tls.getOutputStream();
    reply = read(in);
    if (reply.command != CNXN) throw new IOException("ADB did not accept this app's key");
  }

  private void authenticateLegacy(
      Message first,
      InputStream rawIn,
      OutputStream rawOut,
      PrivateKey key,
      byte[] publicKey,
      boolean sendPublicKey,
      int timeoutMs,
      int authTimeoutMs,
      Runnable onAwaitingAuth)
      throws Exception {
    if (first.arg0 != AUTH_TOKEN)
      throw new IOException("Unexpected ADB AUTH type " + first.arg0);
    if (key == null) throw new IOException("Missing ADB private key");
    write(rawOut, AUTH, AUTH_SIGNATURE, 0, signToken(key, first.data));
    Message reply = read(rawIn);
    if (reply.command == CNXN) return;
    if (reply.command != AUTH || reply.arg0 != AUTH_TOKEN)
      throw new IOException("Unexpected ADB reply " + reply.name());
    if (!sendPublicKey || publicKey == null || publicKey.length == 0)
      throw new UnauthorizedException(
          false, "ADB key is not authorized on port " + raw.getPort());
    write(rawOut, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey);
    if (onAwaitingAuth != null) onAwaitingAuth.run();
    if (authTimeoutMs > 0) raw.setSoTimeout(authTimeoutMs);
    Message authReply;
    try {
      authReply = read(rawIn);
    } catch (IOException error) {
      throw new UnauthorizedException(
          true, "Waiting for USB debugging authorization on the TV", error);
    }
    if (authReply.command != CNXN)
      throw new UnauthorizedException(
          true, "ADB did not authorize this app's key (" + authReply.name() + ")");
    if (timeoutMs > 0) raw.setSoTimeout(timeoutMs);
  }

  /**
   * Signs the 20-byte ADB AUTH_TOKEN using {@code NONEwithRSA} over the 15-byte DER SHA-1 {@code
   * DigestInfo} header concatenated with the raw 20-byte token, matching {@code adbd}'s {@code
   * RSA_sign(NID_sha1, token, 20, ...)} without hashing the token a second time.
   */
  static byte[] signToken(PrivateKey key, byte[] token) throws Exception {
    if (token == null || token.length != TOKEN_BYTES)
      throw new IOException(
          "Unexpected ADB token length " + (token == null ? -1 : token.length));
    byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + TOKEN_BYTES];
    System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
    System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, TOKEN_BYTES);
    Signature signer = Signature.getInstance("NONEwithRSA");
    signer.initSign(key);
    signer.update(digestInfo);
    return signer.sign();
  }

  /** Runs a shell command and returns its combined output, truncated to 64 KiB. */
  String shell(String command) throws IOException {
    write(out, OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (true) {
      Message message = read(in);
      if (message.command == OKAY) continue;
      if (message.command == WRTE) {
        if (output.size() < MAX_OUTPUT)
          output.write(message.data, 0, Math.min(message.data.length, MAX_OUTPUT - output.size()));
        write(out, OKAY, LOCAL_ID, message.arg0, new byte[0]);
      } else if (message.command == CLSE) {
        return output.toString(StandardCharsets.UTF_8.name());
      } else {
        throw new IOException("Unexpected ADB message " + message.name());
      }
    }
  }

  @Override
  public void close() {
    try {
      if (tls != null) tls.close();
    } catch (IOException ignored) {
    }
    try {
      raw.close();
    } catch (IOException ignored) {
    }
  }

  static final class Message {
    final int command, arg0, arg1;
    final byte[] data;

    Message(int command, int arg0, int arg1, byte[] data) {
      this.command = command;
      this.arg0 = arg0;
      this.arg1 = arg1;
      this.data = data;
    }

    String name() {
      byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array();
      return new String(b, StandardCharsets.US_ASCII);
    }
  }

  static byte[] encode(int command, int arg0, int arg1, byte[] data) {
    ByteBuffer header = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
    header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0);
    header.putInt(~command);
    header.put(data);
    return header.array();
  }

  private static void write(OutputStream out, int command, int arg0, int arg1, byte[] data)
      throws IOException {
    out.write(encode(command, arg0, arg1, data));
    out.flush();
  }

  static Message read(InputStream stream) throws IOException {
    DataInputStream in = new DataInputStream(stream);
    byte[] header = new byte[24];
    in.readFully(header);
    ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    int command = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), length = b.getInt();
    b.getInt();
    if (b.getInt() != ~command) throw new IOException("Corrupt ADB message");
    if (length < 0 || length > MAX_PAYLOAD) throw new IOException("ADB message too large");
    byte[] data = new byte[length];
    in.readFully(data);
    return new Message(command, arg0, arg1, data);
  }

  private static X509KeyManager keys(PrivateKey key, X509Certificate certificate) {
    return new X509KeyManager() {
      @Override
      public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) {
        return "adb";
      }

      @Override
      public X509Certificate[] getCertificateChain(String alias) {
        return new X509Certificate[] {certificate};
      }

      @Override
      public PrivateKey getPrivateKey(String alias) {
        return key;
      }

      @Override
      public String[] getClientAliases(String type, Principal[] issuers) {
        return new String[] {"adb"};
      }

      @Override
      public String[] getServerAliases(String type, Principal[] issuers) {
        return null;
      }

      @Override
      public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
        return null;
      }
    };
  }

  private static final class LoopbackTrust implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String type) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
