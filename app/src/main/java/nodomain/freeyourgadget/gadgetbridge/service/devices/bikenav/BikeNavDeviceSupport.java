package nodomain.freeyourgadget.gadgetbridge.service.devices.bikenav;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.view.KeyEvent;

import androidx.camera.core.imagecapture.JpegBytes2Disk;
import androidx.core.graphics.drawable.IconCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.jyou.BFH16Constants;
import nodomain.freeyourgadget.gadgetbridge.devices.pinetime.PineTimeJFConstants;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.GoogleMapsNotificationHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.NavigationInfoSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class BikeNavDeviceSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(BikeNavDeviceSupport.class);
    public final UUID SERIAL_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public final UUID SERIAL_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    public BluetoothGattCharacteristic serialCharacteristic = null;
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();


    private GoogleMapsNotificationHandler gnh = new GoogleMapsNotificationHandler();
    public BikeNavDeviceSupport(Logger logger) {
        super(logger);
    }

    public BikeNavDeviceSupport() {
        super(LOG);
        addSupportedService(SERIAL_SERVICE_UUID);
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        // mark the device as initializing
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // ... custom initialization logic ...

        // set device firmware to prevent the following error when you (later) try to save data to database and
        // device firmware has not been set yet
        // Error executing 'the bind value at index 2 is null'java.lang.IllegalArgumentException: the bind value at index 2 is null
        getDevice().setFirmwareVersion("N/A");
        getDevice().setFirmwareVersion2("N/A");

        //setInitialized(builder);
        //builder.notify(SERIAL_SERVICE_UUID, true);

        serialCharacteristic = getCharacteristic(SERIAL_CHARACTERISTIC_UUID);
        builder.setCallback(this);
        builder.notify(serialCharacteristic, true); // read
        new Packet(TAG.MSG, "Connected to " + Build.MODEL).send(builder);
        syncDateAndTime(builder);
        // mark the device as initialized
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        super.onCharacteristicChanged(gatt, characteristic, value);

        UUID characteristicUUID = characteristic.getUuid();

        if (value.length >= 4) {
            LOG.info("Serial characteristic");
            if (value[0] == 0x55) {
                byte sum = (byte) (value[0] + value[1] + value[2]);
                for (int i = 0; i < value[2]; i++) sum += value[4+i];
                if (sum != value[3]) {
                    LOG.error("Invalid CRC");
                }
                LOG.debug("BT code: " + value[1]);
                switch (value[1]) {
                    case 0x07:
                        batteryCmd.level = value[4];
                        handleGBDeviceEvent(batteryCmd);
                        LOG.info("Battery level is: " + batteryCmd.level);
                        return true;
                    case 0x08:
                        // normal command, e.g. touch
                        byte cmd = value[4];
                        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                        switch (cmd) {
                            case 0x01:
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                                break;
                            case 0x02:
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
                                break;
                            case 0x03:
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                                break;
                            case 0x04:
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP));
                                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP));
                                break;
                            default:
                                LOG.error("Unknown cmd: %d", cmd);
                                break;
                        }
                        return true;
                    default:
                        LOG.error("Unknown tag");
                        break;
                }
            } else {
                LOG.error("Invalid start byte");
            }
            LOG.info("Nothing to decode");
        }

        LOG.info("Characteristic changed UUID: {}", characteristicUUID);
        LOG.info("Characteristic changed value: {}", GB.hexdump(value));
        return false;
    }


    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        String notificationTitle = StringUtils.getFirstOf(notificationSpec.sender, notificationSpec.title);
        byte icon;
        switch (notificationSpec.type) {
            case GENERIC_SMS:
                icon = BFH16Constants.ICON_SMS;
                break;
            case FACEBOOK:
            case FACEBOOK_MESSENGER:
                icon = BFH16Constants.ICON_FACEBOOK;
                break;
            case TWITTER:
                icon = BFH16Constants.ICON_TWITTER;
                break;
            case WHATSAPP:
                icon = BFH16Constants.ICON_WHATSAPP;
                break;
            default:
                icon = BFH16Constants.ICON_LINE;
                break;
        }
        showNotification(icon, notificationTitle, notificationSpec.body);
    }

    public enum TAG {
        TIME(0x01),
        DISTANCE(0x02),
        MSG(0x03),
        IMG(0x04),
        IMG_SIZE(0x05),
        SETTIME(0x06),
        BATTERY_LEVEL(0x07)
        ;

        private byte value;
        TAG(int i) {
            this.value = (byte) i;
        }

        public byte getValue() {
            return this.value;
        }
    }

    class Packet {
        private TAG tag;
        private byte[] value;

        Packet(TAG packettag, int value) {
            this.tag = packettag;
            this.value = new byte[4];
            // java int is BE
            this.value[0] = (byte) (value >> 24);
            this.value[1] = (byte) (value >> 16);
            this.value[2] = (byte) (value >> 8);
            this.value[3] = (byte) (value);
        }

        Packet(TAG packettag, byte[] value) {
            this.tag = packettag;
            this.value = value;
        }

        Packet(TAG packettag, String str) {
            /**
             ** E.g. msg or notification
             */
            this.tag = packettag;
            this.value = new byte[str.getBytes().length + 1];
            System.arraycopy(str.getBytes(), 0, this.value, 0, str.getBytes().length);
            //this.value[this.value.length - 1] = 0;
        }

        Packet(TAG packettag, short year, byte month, byte day, byte hour, byte minute, byte second, byte week) {
            this.tag = packettag;
            this.value = new byte[8];
            this.value[0] = (byte) (year >> 8);
            this.value[1] = (byte) (year & 0xFF);
            this.value[2] = month;
            this.value[3] = day;
            this.value[4] = hour;
            this.value[5] = minute;
            this.value[6] = second;
            this.value[7] = week;
        }

        public byte[] getBlob() {
            byte[] blob = new byte[this.value.length + 4];
            //blob[0] = 0x55;
            //blob[1] = this.tag.getValue();
            //blob[2] = (byte) ((this.value.length >> 8) & 0x000000FF);
            //blob[3] = (byte) (this.value.length & 0x000000FF);
            System.arraycopy(this.value, 0, blob, 4, this.value.length);
            return blob;
        }



        // autoconverted from python script, can be optimized but too lazy
        public byte[] rle_compress(byte[] data, int blksize, int threshold) {
            int index = 0;
            int data_len = data.length;
            ArrayList<byte[]> compressed_data = new ArrayList<>();
            while (index < data_len) {
                int repeat_cnt = get_repeat_count(Arrays.copyOfRange(data, index, data_len), blksize);
                if (repeat_cnt == 0) {
                    // done
                    break;
                } else if (repeat_cnt < threshold) {
                    int nonrepeat_cnt = get_nonrepeat_count(Arrays.copyOfRange(data, index, data_len), blksize, threshold);
                    byte ctrl_byte = (byte) (nonrepeat_cnt | 0x80);
                    compressed_data.add(new byte[]{ctrl_byte});
                    compressed_data.add(Arrays.copyOfRange(data, index, index + nonrepeat_cnt * blksize));
                    index += nonrepeat_cnt * blksize;
                    //LOG.info("NON Repeat " + nonrepeat_cnt);
                } else {
                    byte ctrl_byte = (byte) repeat_cnt;
                    compressed_data.add(new byte[]{ctrl_byte});
                    compressed_data.add(Arrays.copyOfRange(data, index, index + blksize));
                    index += repeat_cnt * blksize;
                    //LOG.info("NON Repeat " + repeat_cnt);

                }
            }

            // Join all byte arrays in compressed_data into one byte array
            int totalLength = 0;
            for (byte[] arr : compressed_data) {
                totalLength += arr.length;
            }
            byte[] result = new byte[totalLength];
            int pos = 0;
            for (byte[] arr : compressed_data) {
                System.arraycopy(arr, 0, result, pos, arr.length);
                pos += arr.length;
            }
            return result;
        }

        public int get_repeat_count(byte[] data, int blksize) {
            if (data.length < blksize) {
                return 0;
            }

            byte[] start = Arrays.copyOfRange(data, 0, blksize);
            int index = 0;
            int repeat_cnt = 0;

            while (index < data.length) {
                if (index + blksize > data.length) {
                    break;
                }
                byte[] value = Arrays.copyOfRange(data, index, index + blksize);

                if (Arrays.equals(value, start)) {
                    repeat_cnt++;
                    if (repeat_cnt == 127) {  // limit max repeat count to max value of signed char.
                        break;
                    }
                } else {
                    break;
                }
                index += blksize;
            }

            return repeat_cnt;
        }

        public int get_nonrepeat_count(byte[] data, int blksize, int threshold) {
            if (data.length < blksize) {
                return 0;
            }

            byte[] pre_value = Arrays.copyOfRange(data, 0, blksize);

            int index = 0;
            int nonrepeat_count = 0;

            int repeat_cnt = 0;
            while (true) {
                if (index + blksize > data.length) {
                    nonrepeat_count += repeat_cnt;
                    break;
                }
                byte[] value = Arrays.copyOfRange(data, index, index + blksize);
                if (Arrays.equals(value, pre_value)) {
                    repeat_cnt++;
                    if (repeat_cnt > threshold) {
                        // repeat found.
                        break;
                    }
                } else {
                    pre_value = value;
                    nonrepeat_count += 1 + repeat_cnt;
                    repeat_cnt = 0;
                    if (nonrepeat_count >= 127) {  // limit max repeat count to max value of signed char.
                        nonrepeat_count = 127;
                        break;
                    }
                }

                index += blksize;  // move to next position
                if (index >= data.length) {  // data end
                    nonrepeat_count += repeat_cnt;
                    break;
                }
            }

            return nonrepeat_count;
        }



        public void send(TransactionBuilder builder) {
            if (this.value.length < 1024) {
                int length = this.value.length;
                while (length > 0) {
                    int block = length > 16 ? 16 : length;
                    byte[] drop = new byte[block + 4];
                    System.arraycopy(this.value, this.value.length - length, drop, 4, block);
                    if (block == length) drop[0] = (byte) 0x55;
                    else if (length == this.value.length) drop[0] = (byte) 0xAA;
                    else drop[0] = (byte) 0xF0;
                    drop[1] = this.tag.getValue();
                    drop[2] = (byte) (block & 0x000000FF);
                    drop[3] = (byte) (drop[0] + drop[1] + drop[2]);
                    for (int i = 0; i < block; i++) drop[3] += drop[4 + i];
                    //LOG.info(HexFormat.of().formatHex(drop));
                    builder.write(SERIAL_CHARACTERISTIC_UUID, drop);
                    length -= block;
                }
            } else {

                /*for (int d = 0; d < 32; d++) {
                    byte[] comp = rle_compress(this.value, 1, d);

                    // redo into 4/2/1 bpp
                    byte[] bpp4 = new byte[128 * 64];
                    byte[] bpp2 = new byte[128 * 32];
                    byte[] bpp1 = new byte[128 * 16];
                    int x4 = 0, x2 = 0, x1 = 0;
                    for (int j = 0; j < 128; j++) {
                        byte n4 = 0, n2 = 0, n1 = 0;
                        for (int i = 0; i < 128; i++) {
                            n4 |= (this.value[i * j] >> 4) << (4 - (4 * ((i * j) % 2)));
                            n2 |= (this.value[i * j] >> 6) << (6 - (2 * ((i * j) % 4)));
                            n1 |= (this.value[i * j] >> 8) << (8 - ((i * j) % 8));
                            if ((j * i) % 2 == 1) {
                                bpp4[x4] = n4;
                                x4++;
                                n4 = 0;
                            }
                            if ((j * i) % 4 == 3) {
                                bpp2[x2] = n2;
                                x2++;
                                n2 = 0;
                            }
                            if ((j * i) % 8 == 7) {
                                bpp1[x1] = n1;
                                x1++;
                                n1 = 0;
                            }
                        }
                    }
                    byte[] comp4 = rle_compress(bpp4, 1, d);
                    byte[] comp2 = rle_compress(bpp2, 1, d);
                    byte[] comp1 = rle_compress(bpp2, 1, d);
                    LOG.info("TH: " + d);
                    LOG.info("LARGE MESSAGE, NOT SENDING: orignal: " + this.value.length + " :: rle : " + comp.length);
                    LOG.info("LARGE MESSAGE, NOT SENDING: orignal: " + bpp4.length + " :: rle : " + comp4.length);
                    LOG.info("LARGE MESSAGE, NOT SENDING: orignal: " + bpp2.length + " :: rle : " + comp2.length);
                    LOG.info("LARGE MESSAGE, NOT SENDING: orignal: " + bpp1.length + " :: rle : " + comp1.length);
                }*/
                byte[] compdata = rle_compress(this.value, 1, 4); // 4 is about right for icons
                byte[] comp = new byte[compdata.length + 12];
                System.arraycopy(compdata, 0, comp, 12, compdata.length);
                comp[0] = 1;
                comp[5] = (byte) (compdata.length >> 8);
                comp[4] = (byte) (compdata.length & 0xFF);
                //LOG.info(String.format("%d :: %02X%02X", compdata.length, (byte) (compdata.length >> 8), (byte) (compdata.length & 0xFF)));
                comp[9] = (byte) (this.value.length >> 8);
                comp[8] = (byte) (this.value.length & 0xFF);
                //LOG.info(String.format("%d :: %02X%02X", this.value.length, (byte) (this.value.length >> 8), (byte) (this.value.length & 0xFF)));
                int remainder = comp.length;
                byte[] vlu = comp.clone();
                while(remainder > 0) {
                    int length = remainder > 1024 ? 1024 : remainder;
                    int index = 0;
                    while (length > 0) {
                        int block = length > 16 ? 16 : length;
                        byte[] drop = new byte[block + 4];
                        System.arraycopy(vlu, index, drop, 4, block);
                        if (block == length) drop[0] = (byte) 0x55;
                        else if (length == 1024 || length == remainder) drop[0] = (byte) 0xAA;
                        else drop[0] = (byte) 0xF0;
                        drop[1] = this.tag.getValue();
                        drop[2] = (byte) (block & 0x000000FF);
                        drop[3] = (byte) (drop[0] + drop[1] + drop[2]);
                        for (int i = 0; i < block; i++) drop[3] += drop[4 + i];
                        //LOG.info(HexFormat.of().formatHex(drop));
                        builder.write(SERIAL_CHARACTERISTIC_UUID, drop);
                        index += block;
                        length -= block;
                    }
                    remainder -= 1024;
                    if (remainder > 0) vlu = Arrays.copyOfRange(vlu, 1024, vlu.length);
                }

                // done send img size
                new Packet(TAG.IMG_SIZE, comp.length).send(builder);
            }
        }
    }

    private void showNotification(byte icon, String title, String message) {
        try {
            TransactionBuilder builder = performInitialized("ShowNotification");

            byte[] titleBytes = title.getBytes();
            byte[] messageBytes = message.getBytes();
            LOG.info("NOTIFICATION!!!");
            new Packet(TAG.MSG, title + "\n" + message).send(builder);

            /*for (int i = 1; i <= 7; i++)
            {
                byte[] currentPacket = new byte[20];
                currentPacket[0] = BFH16Constants.CMD_ACTION_SHOW_NOTIFICATION;
                currentPacket[1] = 7;
                currentPacket[2] = (byte)i;
                switch(i) {
                    case 1:
                        currentPacket[4] = icon;
                        break;
                    case 2:
                        if (titleBytes != null) {
                            System.arraycopy(titleBytes, 0, currentPacket, 3, 6);
                            System.arraycopy(titleBytes, 6, currentPacket, 10, 10);
                        }
                        break;
                    default:
                        if (messageBytes != null) {
                            System.arraycopy(messageBytes, 16 * (i - 3), currentPacket, 3, 6);
                            System.arraycopy(messageBytes, 6 + 16 * (i - 3), currentPacket, 10, 10);
                        }
                        break;
                }
                builder.write(serialCharacteristic, currentPacket);
            }*/


            builder.queue();
        } catch (IOException e) {
            LOG.warn(e.getMessage());
        }
    }

    private NavigationInfoSpec navspec = new NavigationInfoSpec();

    @Override
    public void onSetNavigationInfo(NavigationInfoSpec navigationInfoSpec) {
        LOG.info(navigationInfoSpec.toString());
        TransactionBuilder builder = createTransactionBuilder("navigation info");

        // check for updates
        if (!Objects.equals(this.navspec.distanceToTurn, navigationInfoSpec.distanceToTurn) && navigationInfoSpec.distanceToTurn != null) {
            this.navspec.distanceToTurn = navigationInfoSpec.distanceToTurn;
            int distance = 0;
            if (navigationInfoSpec.distanceToTurn != null && navigationInfoSpec.distanceToTurn.contains("km")) {
                String[] split = navigationInfoSpec.distanceToTurn.split("\\s+");
                LOG.info(Arrays.toString(split));
                float ds = Float.parseFloat(split[0]);
                distance = (int) (1000 * ds);
            } else if (navigationInfoSpec.distanceToTurn != null && navigationInfoSpec.distanceToTurn.contains("m")) {
                String[] split = navigationInfoSpec.distanceToTurn.split("\\s+");
                LOG.info(Arrays.toString(split));
                distance = Integer.parseInt(split[0]);
            }
            LOG.info("Sending distance: " + distance + " " + navigationInfoSpec.distanceToTurn) ;

            if (distance != 0) {
                new Packet(TAG.DISTANCE, distance).send(builder);
            }
        }

        if (!Objects.equals(this.navspec.timeLeft, navigationInfoSpec.timeLeft) && navigationInfoSpec.timeLeft != null) {
            this.navspec.timeLeft = navigationInfoSpec.timeLeft;
            int time = 0;
            if (navigationInfoSpec.timeLeft.contains("hr")) {
                String[] split = navigationInfoSpec.timeLeft.split("\\s+");
                LOG.info(Arrays.toString(split));

                int hr = Integer.parseInt(split[0]);
                int min = Integer.parseInt(split[2]);
                time = (hr * 3600) + (min * 60);
            } else if (navigationInfoSpec.timeLeft.contains("min")) {
                String[] split = navigationInfoSpec.timeLeft.split("\\s+");
                LOG.info(Arrays.toString(split));

                int min = Integer.parseInt(split[0]);
                time = (min * 60);
            }
            LOG.info("Sending time: " + time + " " + navigationInfoSpec.timeLeft);

            if (time > 0) {
                new Packet(TAG.TIME, time).send(builder);
            }
        } else {
            LOG.info(navigationInfoSpec.timeLeft);
        }
        if (!Objects.equals(this.navspec.instruction, navigationInfoSpec.instruction) && navigationInfoSpec.instruction != null) {
            this.navspec.instruction = navigationInfoSpec.instruction;
            if (navigationInfoSpec.instruction != null) {
                new Packet(TAG.MSG, navigationInfoSpec.instruction).send(builder);
            }
        }

        //safeWriteToCharacteristic(builder, PineTimeJFConstants.UUID_CHARACTERISTICS_NAVIGATION_NARRATIVE, navigationInfoSpec.instruction.getBytes(StandardCharsets.UTF_8));
        //safeWriteToCharacteristic(builder, PineTimeJFConstants.UUID_CHARACTERISTICS_NAVIGATION_MAN_DISTANCE, navigationInfoSpec.distanceToTurn.getBytes(StandardCharsets.UTF_8));

        LOG.info("Navigation spec:\n" +
                "dist to turn: " + navigationInfoSpec.distanceToTurn + "\n" +
                "instruction: " + navigationInfoSpec.instruction + "\n" +
                "next action: " + navigationInfoSpec.nextAction + "\n" +
                "Time left: " + navigationInfoSpec.timeLeft + "\n" +
                "ETA: " + navigationInfoSpec.ETA + "\n"
        );

        /*int[] icon = gnh.getIcon(navigationInfoSpec.nextAction);
        // convert icon to bitmap
        byte[] iconBytes = new byte[icon.length % 8 == 0 ? icon.length / 8 : (icon.length / 8) + 1];
        for (int i = 0; i < icon.length; i++) {
            iconBytes[i / 8] |= (icon[i] << (7 - (i % 8)));
        }
        LOG.info("Icon: " + icon.length);
        LOG.info("Icon: " + iconBytes.length);
*/
        if (navigationInfoSpec.icon != null && !Arrays.equals(navigationInfoSpec.icon, navspec.icon)) {
            navspec.icon = navigationInfoSpec.icon;
            new Packet(TAG.IMG, navigationInfoSpec.icon).send(builder);
        }
        //builder.write(SERIAL_CHARACTERISTIC_UUID, new Packet(TAG.IMG, iconBytes).getBlob());

        //safeWriteToCharacteristic(builder, PineTimeJFConstants.UUID_CHARACTERISTICS_NAVIGATION_FLAGS, iconname.getBytes(StandardCharsets.UTF_8));
        builder.queue();
    }

    private void syncDateAndTime(TransactionBuilder builder) {
        Calendar cal = Calendar.getInstance();
        String strYear = String.valueOf(cal.get(Calendar.YEAR));
        short year = (byte)Integer.parseInt(strYear.substring(0, 4));
        byte month = (byte)cal.get(Calendar.MONTH);
        byte day = (byte)cal.get(Calendar.DAY_OF_MONTH);
        byte hour = (byte)cal.get(Calendar.HOUR_OF_DAY);
        byte minute = (byte)cal.get(Calendar.MINUTE);
        byte second = (byte)cal.get(Calendar.SECOND);
        byte week = (byte)cal.get(Calendar.WEEK_OF_YEAR);

        new Packet(TAG.SETTIME, year, month, day, hour, minute, second, week).send(builder);
    }

    @Override
    public void onSetTime() {
        LOG.info("SETTIME");
        try {
            TransactionBuilder builder = performInitialized("SetTime");
            syncDateAndTime(builder);
            builder.queue();

        } catch(IOException e) {
            LOG.warn(e.getMessage());
        }
    }

}
