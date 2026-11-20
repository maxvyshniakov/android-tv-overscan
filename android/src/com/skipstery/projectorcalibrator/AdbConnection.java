package com.skipstery.projectorcalibrator;

import java.io.ByteArrayOutputStream;
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
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal client for the device's own adbd over TLS (Wireless debugging), enough to run one
 * shell command. adbd authenticates us by the paired client key; we connect only to the loopback
 * interface, so its self-signed server certificate is not checked.
 */
final class AdbConnection implements AutoCloseable {
  static final int CNXN = 0x4e584e43, STLS = 0x534c5453, OPEN = 0x4e45504f;
  static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;
  static final int VERSION = 0x01000001, STLS_VERSION = 0x01000000, MAX_PAYLOAD = 1 << 20;
  private static final int LOCAL_ID = 1, MAX_OUTPUT = 64 * 1024;

  private final Socket raw;
  private SSLSocket tls;
  private InputStream in;
  private OutputStream out;

  private AdbConnection(Socket raw) {
    this.raw = raw;
  }

  static AdbConnection open(
      int port, PrivateKey key, X509Certificate certificate, int timeoutMs) throws Exception {
    Socket raw = new Socket();
    AdbConnection connection = new AdbConnection(raw);
    try {
      raw.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
      raw.setSoTimeout(timeoutMs);
      connection.handshake(key, certificate);
      return connection;
    } catch (Exception error) {
      connection.close();
      throw error;
    }
  }

  private void handshake(PrivateKey key, X509Certificate certificate) throws Exception {
    write(raw.getOutputStream(), CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
    Message reply = read(raw.getInputStream());
    if (reply.command == AUTH)
      throw new IOException("This port uses legacy ADB authentication, not Wireless debugging");
    if (reply.command != STLS) throw new IOException("Unexpected ADB reply " + reply.name());
    write(raw.getOutputStream(), STLS, STLS_VERSION, 0, new byte[0]);
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(new KeyManager[] {keys(key, certificate)}, new TrustManager[] {new LoopbackTrust()}, null);
    tls =
        (SSLSocket) context.getSocketFactory().createSocket(raw, "127.0.0.1", raw.getPort(), true);
    tls.setEnabledProtocols(new String[] {"TLSv1.3"});
    tls.startHandshake();
    in = tls.getInputStream();
    out = tls.getOutputStream();
    reply = read(in);
    if (reply.command != CNXN) throw new IOException("ADB did not accept this app's key");
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
