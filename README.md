# OPC2Serial
Lightweight proxy to convert OPC to various Serial protocols.

```
Usage:
 -oa,--opc-address      Network address to bind (default 127.0.0.1)
 -op,--opc-port         UDP port to listen to (default 7890)
 -oc,--opc-channel      OPC channel to listen for (default 0)
 -sp,--serial-port      Serial port for output
 -p,--serial-protocol   Protocol for serial output (ADALIGHT,AWA,TPM2)
 -br,--serial-baud-rate Serial port baud rate
 -list,--list-ports     List available serial ports
 -d,--debug             Log debugging output
 -h,--help              Display CLI arguments
```

### Build
```
$ mvn clean package
```

### Identify the serial port
```
$ java -jar target/target/opc2serial-1.0.0-jar-with-dependencies.jar -list
Available serial ports:
/dev/cu.Bluetooth-Incoming-Port
/dev/cu.debug-console
/dev/tty.Bluetooth-Incoming-Port
/dev/tty.debug-console
```

### Run
```
$ java -jar target/opc2serial-1.0.0-jar-with-dependencies.jar -sp /dev/cu.debug-console -p AWA --debug
OPC: 127.0.0.1 on UDP port 7890
Serial: AWA at 2000000 baud on port /dev/cu.debug-console
Starting OPC->Serial proxy loop...
[1] Forwarding RGB payload of 30 bytes
[2] Forwarding RGB payload of 30 bytes
[3] Forwarding RGB payload of 30 bytes
[4] Forwarding RGB payload of 30 bytes
```
