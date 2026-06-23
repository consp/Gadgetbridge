package nodomain.freeyourgadget.gadgetbridge.devices.bikenav;

import static nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator.DeviceKind.BIKE_COMPUTER;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.bikenav.BikeNavDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.unknown.UnknownDeviceSupport;

public class BikeNavDeviceCoordinator extends AbstractDeviceCoordinator {

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_bikenav;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return BIKE_COMPUTER;
    }

    @Override
    public String getManufacturer() {
        return "consp";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return BikeNavDeviceSupport.class;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile(".*Bike.*");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean supportsCyclingData(@NonNull GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsNavigation(@NonNull final GBDevice device) {
        return true;
    }

//    @Override
//    public boolean supports
}
