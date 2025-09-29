/**
 * MIT License
 *
 * Copyright (c) 2025 Mark C. Slee, Heron Arts LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.heronarts;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortList;
import jssc.SerialPortTimeoutException;

/**
 * Minimal CLI utility that reads OPC pixel data from a UDP port
 * and relays the data a serial port using specified protocol.
 */
public class OPC2Serial {

  public static final String OPC_DEFAULT_ADDRESS = "127.0.0.1";
  public static final int OPC_DEFAULT_PORT = 7890;
  public static final byte OPC_COMMAND_SET_PIXEL_COLORS = 0;
  public static final byte OPC_CHANNEL_BROADCAST = 0;
  public static final int OPC_HEADER_LEN = 4;
  public static final int OPC_RGB_PAYLOAD = OPC_HEADER_LEN;

  public static final int ADALIGHT_HANDSHAKE_TIMEOUT = 5000;
  public static final int ADALIGHT_RGB_PAYLOAD = 6;

  public static final int AWA_CHECKSUM_LEN = 2;

  public static final byte TPM2_START_BYTE = (byte) 0xC9;
  public static final byte TPM2_DATA_FRAME = (byte) 0xDA;
  public static final int TPM2_RGB_PAYLOAD = 4;
  public static final byte TPM2_END_BYTE = 0x36;

  public enum SerialProtocol {
    ADALIGHT(115200, ADALIGHT_RGB_PAYLOAD, 0),
    AWA(2000000, ADALIGHT_RGB_PAYLOAD, AWA_CHECKSUM_LEN),
    TPM2(115200, TPM2_RGB_PAYLOAD, 1);

    private final int defaultBaudRate;
    private final int headerLen;
    private final int footerLen;

    private SerialProtocol(int defaultBaudRate, int headerLen, int footerLen) {
      this.defaultBaudRate = defaultBaudRate;
      this.headerLen = headerLen;
      this.footerLen = footerLen;
    }

    public static String names() {
      String[] names = new String[SerialProtocol.values().length];
      int i = 0;
      for (SerialProtocol protocol : SerialProtocol.values()) {
        names[i++] = protocol.name();
      }
      return String.join(",", names);
    }
  };

  public static class Flags {
    public String opcAddress = OPC_DEFAULT_ADDRESS;
    public int opcPort = OPC_DEFAULT_PORT;
    public int opcChannel = OPC_CHANNEL_BROADCAST;
    public String serialPort = null;
    public SerialProtocol serialProtocol = SerialProtocol.ADALIGHT;
    public int serialBaudRate = -1;
    public boolean debug = false;
  }

  public static final Flags FLAGS = new Flags();

  public interface ParseValue {
    public void parseValue(String s);
  }

  public enum Arg {
    OPC_ADDRESS("-oa", "--opc-address", "Network address to bind (default " + OPC_DEFAULT_ADDRESS + ")", s -> FLAGS.opcAddress = s),
    OPC_PORT("-op", "--opc-port", "UDP port to listen to (default " + OPC_DEFAULT_PORT + ")", s -> FLAGS.opcPort = Integer.parseInt(s)),
    OPC_CHANNEL("-oc", "--opc-channel", "OPC channel to listen for (default " + OPC_CHANNEL_BROADCAST + ")", s -> FLAGS.opcChannel = Integer.parseInt(s)),
    SERIAL_PORT("-sp", "--serial-port", "Serial port for output", s -> FLAGS.serialPort = s),
    SERIAL_PROTOCOL("-p", "--serial-protocol", "Protocol for serial output (" + SerialProtocol.names() + ")", s -> FLAGS.serialProtocol = SerialProtocol.valueOf(s.toUpperCase())),
    SERIAL_BAUD("-br", "--serial-baud-rate", "Serial port baud rate", s -> FLAGS.serialBaudRate = Integer.valueOf(s)),
    LIST_PORTS("-list", "--list-ports", "List available serial ports", () -> {
      System.out.println("Available serial ports:");
      for (String portName : SerialPortList.getPortNames()) {
        System.out.println(portName);
      }
      exit();
    }),
    DEBUG("-d", "--debug", "Log debugging output", () -> FLAGS.debug = true),
    HELP("-h", "--help", "Display CLI arguments", () -> {
      System.out.println("Usage:");
      for (Arg a : Arg.values()) {
        String prefix = " " + a.shortName + "," + a.longName;
        System.out.print(prefix);
        for (int i = 0; i < 24 - prefix.length(); ++i) {
          System.out.print(" ");
        }
        System.out.println(a.description);
      }
      System.exit(0);
    });

    public final String shortName, longName, description;
    public final Runnable run;
    public final ParseValue parseValue;

    private Arg(String shortName, String longName, String description, Runnable run) {
      this(shortName, longName, description, run, null);
    }

    private Arg(String shortName, String longName, String description, ParseValue parseValue) {
      this(shortName, longName, description, null, parseValue);
    }

    private Arg(String shortName, String longName, String description, Runnable run, ParseValue parseValue) {
      this.shortName = shortName;
      this.longName = longName;
      this.description = description;
      this.run = run;
      this.parseValue = parseValue;
    }
  }

  public static void exit() {
    exit(null);
  }

  public static void exit(String message) {
    if (message != null) {
      System.out.println(message);
    }
    System.exit(0);
  }

  public static void main(String[] args) {
    try {
      parseArgs(args);
      if (FLAGS.serialPort == null) {
        exit("No serial port specified, specify via " + Arg.SERIAL_PORT.shortName + " or " + Arg.SERIAL_PORT.longName);
      }
      run();
    } catch (Throwable x) {
      System.err.println("Unhandled exception in main(): " + x.getMessage());
      x.printStackTrace(System.err);
    }
  }

  private static void run() {
    final int baudRate = FLAGS.serialBaudRate > 0 ?
      FLAGS.serialBaudRate :
      FLAGS.serialProtocol.defaultBaudRate;

    System.out.println("OPC: " + FLAGS.opcAddress + " on UDP port " + FLAGS.opcPort);
    System.out.println("Serial: " + FLAGS.serialProtocol + " at " + baudRate + " baud on port " + FLAGS.serialPort);

    final SerialPort serialPort = new SerialPort(FLAGS.serialPort);
    try {
      serialPort.openPort();
      serialPort.setParams(
        baudRate,
        SerialPort.DATABITS_8,
        SerialPort.STOPBITS_1,
        SerialPort.PARITY_NONE
      );

      final byte[] inputData = new byte[4096];

      if (FLAGS.serialProtocol == SerialProtocol.ADALIGHT) {
        try {
          System.out.println("Waiting " + ADALIGHT_HANDSHAKE_TIMEOUT + "ms for Ada\\n handshake...");
          byte[] handshake = serialPort.readBytes(4, ADALIGHT_HANDSHAKE_TIMEOUT);
          boolean valid = true;
          valid = valid && (handshake[0] == 'A');
          valid = valid && (handshake[1] == 'd');
          valid = valid && (handshake[2] == 'a');
          valid = valid && (handshake[3] == '\n');
          if (!valid) {
            System.err.println("Did not receive valid Ada\\n handshake from Arduino, aborting.");
            serialPort.closePort();
            return;
          }
        } catch (SerialPortTimeoutException e) {
          System.err.println("Timeout waiting for Adalight handshake, aborting.");
          serialPort.closePort();
          return;
        }
      }

      try (DatagramSocket datagramSocket = new DatagramSocket(FLAGS.opcPort, InetAddress.getByName(FLAGS.opcAddress))) {
        System.out.println("Starting OPC->Serial proxy loop...");
        int packetCount = 0;
        final DatagramPacket packet = new DatagramPacket(inputData, inputData.length);
        while (true) {
          datagramSocket.receive(packet);
          final int packetLength = packet.getLength();
          if (packetLength < OPC_HEADER_LEN) {
            System.err.println("Ignoring OPC packet received with length < " + OPC_HEADER_LEN);
            continue;
          }
          final int opcChannel = 0xff & inputData[0];
          if ((opcChannel != OPC_CHANNEL_BROADCAST) && (opcChannel != FLAGS.opcChannel)) {
            System.err.println("Ignoring OPC message on channel " + opcChannel);
            continue;
          }
          final byte opcCommand = inputData[1];
          if (opcCommand != OPC_COMMAND_SET_PIXEL_COLORS) {
            System.err.println("Unrecognized OPC command: " + String.format("0x%X", opcCommand));
            continue;
          }
          final int opcDataLen = (inputData[2] << 8) | (inputData[3] & 0xff);
          if (OPC_RGB_PAYLOAD + opcDataLen > inputData.length) {
            System.err.println("Ignoring OPC message with excessive length: " + opcDataLen);
            continue;
          }
          if (OPC_RGB_PAYLOAD + opcDataLen > packetLength) {
            System.err.println("Ignoring OPC message with partial payload data: " + opcDataLen);
            continue;
          }

          // NOTE(mcslee): it's a serious bummer that jssc does not offer an API on SerialPort that
          // takes start/len on a byte array, in which case we could use one persistent byte[].
          // This beans per-frame byte[] allocation, but we trust this to be a walk in the park for
          // the JVM.
          final byte[] outputData = new byte[FLAGS.serialProtocol.headerLen + opcDataLen + FLAGS.serialProtocol.footerLen];
          switch (FLAGS.serialProtocol) {
            case ADALIGHT, AWA -> {
              outputData[0] = 'A';
              outputData[1] = (FLAGS.serialProtocol == SerialProtocol.AWA) ? (byte) 'w' : (byte) 'd';
              outputData[2] = 'a';

              if (opcDataLen % 3 != 0) {
                System.err.println("OPC data length does not appear to be RGB: " + opcDataLen);
              }

              // NOTE(mcslee): this ledCount-1 does seem funky but that is how it's done in Adalight
              // https://github.com/adafruit/Adalight/blob/master/Processing/Adalight/Adalight.pde
              // So a value of [MSB=0x00, LSB=0x01] does seem to mean 2 LEDs (6 RGB bytes)
              final int ledCount = opcDataLen / 3;
              outputData[3] = (byte) ((ledCount-1) >> 8);
              outputData[4] = (byte) ((ledCount-1) & 0xff);
              outputData[5] = (byte) (outputData[3] ^ outputData[4] ^ 0x55);
            }
            case TPM2 -> {
              outputData[0] = TPM2_START_BYTE;
              outputData[1] = TPM2_DATA_FRAME;
              outputData[2] = (byte) (opcDataLen >> 8);
              outputData[3] = (byte) (0xff & opcDataLen);
              outputData[outputData.length - 1] = TPM2_END_BYTE;
            }
          }

          // Copy the RGB payload
          System.arraycopy(inputData, OPC_RGB_PAYLOAD, outputData, FLAGS.serialProtocol.headerLen, opcDataLen);

          if (FLAGS.serialProtocol == SerialProtocol.AWA) {
            // Additional Fletcher checksum over RGB bytes
            int fletcher1 = 0, fletcher2 = 0;
            for (int i = 0; i < opcDataLen; ++i) {
              int dmx = 0xff & outputData[ADALIGHT_RGB_PAYLOAD + i];
              fletcher1 = (fletcher1 + dmx) % 255;
              fletcher2 = (fletcher2 + fletcher1) % 255;
            }
            outputData[ADALIGHT_RGB_PAYLOAD + opcDataLen] = (byte) fletcher1;
            outputData[ADALIGHT_RGB_PAYLOAD + opcDataLen + 1] = (byte) fletcher2;
          }

          // Send to the serial port
          if (FLAGS.debug) {
            ++packetCount;
            System.out.println("[" + packetCount + "] Forwarding RGB payload of " + opcDataLen + " bytes");
          }
          serialPort.writeBytes(outputData);
        }

      } catch (SocketException sx) {
        System.err.println("UDP socket error: " + sx.getMessage());
        sx.printStackTrace(System.err);
        serialPort.closePort();
      } catch (UnknownHostException uhx) {
        System.err.println("Could not bind to host: " + uhx.getMessage());
        serialPort.closePort();
      } catch (IOException iox) {
        System.err.println("UDP socket could not receive packet: " + iox.getMessage());
        serialPort.closePort();
      }

    } catch (SerialPortException spx) {
      System.err.println("Error on serial port: " + FLAGS.serialPort);
      spx.printStackTrace(System.err);
    }
  }

  private static void parseArgs(String[] args) throws Exception {
    int i = 0;
    while (i < args.length) {
      final String arg = args[i];
      boolean unknownArg = true;
      for (Arg a : Arg.values()) {
        if (arg.equals(a.shortName) || arg.equals(a.longName)) {
          if (a.parseValue != null) {
            ++i;
            if (i >= args.length) {
              throw new Exception("Argument " + arg + " requires a value");
            }
            a.parseValue.parseValue(args[i]);
          }
          if (a.run != null) {
            a.run.run();
          }
          unknownArg = false;
          break;
        }
      }
      if (unknownArg) {
        System.out.println("Unrecognized CLI argument: " + arg);
      }
      ++i;
    }
  }
}
