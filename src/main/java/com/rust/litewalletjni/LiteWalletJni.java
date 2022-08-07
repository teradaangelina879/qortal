/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * LiteWalletJni code based on https://github.com/PirateNetwork/cordova-plugin-litewallet
 *
 * MIT License
 *
 * Copyright (c) 2020 Zero Currency Coin
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
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.rust.litewalletjni;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LiteWalletJni {

    protected static final Logger LOGGER = LogManager.getLogger(LiteWalletJni.class);

    public static native String initlogging();
    public static native String initnew(final String serveruri, final String params, final String saplingOutputb64, final String saplingSpendb64);
    public static native String initfromseed(final String serveruri, final String params, final String seed, final String birthday, final String saplingOutputb64, final String saplingSpendb64);
    public static native String initfromb64(final String serveruri, final String params, final String datab64, final String saplingOutputb64, final String saplingSpendb64);
    public static native String save();

    public static native String execute(final String cmd, final String args);
    public static native String getseedphrase();
    public static native String getseedphrasefromentropyb64(final String entropy64);
    public static native String checkseedphrase(final String input);


    private static boolean loaded = false;

    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        String osName = System.getProperty("os.name");
        String osArchitecture = System.getProperty("os.arch");

        LOGGER.info("OS Name: {}", osName);
        LOGGER.info("OS Architecture: {}", osArchitecture);

        try {
            String libPath;

            if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64")) {
                libPath = "librust.dylib";
            }
            else if (osName.equals("Linux") && osArchitecture.equals("aarch64")) {
                libPath = "/home/pi/librust.so";
            }
            else if (osName.equals("Linux") && osArchitecture.equals("amd64")) {
                libPath = "/etc/qortal/librust.so";
            }
            else if (osName.contains("Windows") && osArchitecture.equals("amd64")) {
                libPath = "C:\\Users\\User\\Repositories\\pirate-litewalletjni\\src\\target\\release\\rust.dll";
            }
            else {
                LOGGER.info("Library not found for OS: {}, arch: {}", osName, osArchitecture);
                return;
            }

            System.load(libPath);
            loaded = true;
        }
        catch (UnsatisfiedLinkError e) {
            LOGGER.info("Unable to load library");
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

}
