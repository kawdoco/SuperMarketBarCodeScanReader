# Barcode Wedge + Product Lookup + Label Print (Java 11, macOS)

This project is for **USB 1D/2D scanners that work like a keyboard** (it types into TextEdit/Notepad).
It supports:

- Scan into the app (ENTER after scan)
- Lookup product details from `products.csv`
- Preview a label (name + price + barcode)
- Print the label using your macOS printer dialog
- Keep a scan log

## Requirements

- Java 11+
- Maven

Check:
```bash
java -version
mvn -version
```

## Run

```bash
cd barcode-wedge-lookup-print
mvn -q exec:java
```

## Product database

Edit this file:

`src/main/resources/products.csv`

Format:
```
code,name,price
ABC-1001,USB Cable 1m,650.00
```

After editing, click **Reload products** in the app (or restart).

## Label size

Open `BarcodePOSApp.java` and adjust:

```java
private static final double LABEL_W_MM = 58;
private static final double LABEL_H_MM = 40;
```

Common sizes: 58x40mm, 50x30mm, 100x50mm.

## Notes

- If your scanner ends with TAB instead of ENTER, set:
  `ACCEPT_TAB_AS_END = true;`
