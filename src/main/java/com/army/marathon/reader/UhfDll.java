// src/main/java/com/army/marathon/reader/UhfDll.java
package com.army.marathon.reader;

import java.io.File;
import com.army.marathon.config.AppPaths;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.ByteByReference;

public interface UhfDll extends Library {
    
    // DLL Path
    File DLL_FILE = new File(AppPaths.libDir(), "UHFReader18.dll");
    String DLL_PATH = DLL_FILE.getAbsolutePath();

    // INSTANCE - This is the interface to call DLL methods
    UhfDll INSTANCE = Native.load(DLL_PATH, UhfDll.class);

    // Auto detect COM port
    int AutoOpenComPort(IntByReference port,
                        ByteByReference comAdr,
                        byte baud,
                        IntByReference frmHandle);

    // Open specific COM port
    int OpenComPort(int port,
                    ByteByReference comAdr,
                    byte baud,
                    IntByReference frmHandle);

    // Close COM port
    int CloseComPort();

    // EPC inventory
    int Inventory_G2(ByteByReference comAdr,
                     byte adrTID,
                     byte lenTID,
                     byte tidFlag,
                     byte[] epcBuffer,
                     IntByReference totalLen,
                     IntByReference cardNum,
                     int frmHandle);
}
