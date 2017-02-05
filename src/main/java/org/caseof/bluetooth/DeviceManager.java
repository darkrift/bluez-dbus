package org.caseof.bluetooth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluez.Adapter1;
import org.bluez.Device1;
import org.caseof.DbusHelper;
import org.caseof.bluetooth.wrapper.BluetoothAdapter;
import org.caseof.bluetooth.wrapper.BluetoothDevice;
import org.freedesktop.dbus.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;

import cz.adamh.utils.NativeUtils;

public class DeviceManager {

    private static DeviceManager INSTANCE;
    private DBusConnection dbusConnection;

    private final Map<String, BluetoothAdapter> bluetoothAdaptersByMac = new LinkedHashMap<>();
    private final Map<String, BluetoothAdapter> bluetoothAdaptersByAdapterName = new LinkedHashMap<>();

    private final Map<String, List<BluetoothDevice>> bluetoothDeviceByAdapterMac = new LinkedHashMap<>();

    private String defaultAdapterMac;

    private static boolean libraryLoaded = false;

    private static void loadLibrary() {
        if (!libraryLoaded) {
            try {
                System.loadLibrary("libunix-java"); // look for globally installed
            } catch (UnsatisfiedLinkError _ex) {
                try {
                    String sysArch = System.getProperty("os.arch");
                    if (sysArch != null) {
                        if (sysArch.equalsIgnoreCase("x86_64") || sysArch.equalsIgnoreCase("amd64")) {
                            NativeUtils.loadLibraryFromJar("/lib/libunix-java_amd64.so");
                        }
                    }
                } catch (IOException _ex2) {
                    throw new RuntimeException(_ex2);
                }
            }
            libraryLoaded = true;
        }
    }

    /**
     * Private constructor for singleton pattern.
     *
     * @param _connection
     */
    private DeviceManager(DBusConnection _connection) {
        dbusConnection = _connection;
    }

    /**
     * Create a new {@link DeviceManager} using the UnixDomainSockets and use either the global SYSTEM interface
     * or create a new interface just for this session (if _sessionConnection = true).
     *
     * @param _sessionConnection true to create user-session, false to use system session
     */
    public static DeviceManager createInstance(boolean _sessionConnection) throws DBusException {
        loadLibrary();
        INSTANCE = new DeviceManager(DBusConnection.getConnection(_sessionConnection ? DBusConnection.SESSION : DBusConnection.SYSTEM));
        return INSTANCE;
    }

    public void closeConnection() {
        dbusConnection.disconnect();
    }

    /**
     * Create a new {@link DeviceManager} instance using the given DBus address (e.g. tcp://127.0.0.1:13245)
     * @param _address
     * @throws DBusException
     */
    public static DeviceManager createInstance(String _address) throws DBusException {
        if (_address == null) {
            throw new DBusException("Null is not a valid address");
        }
        if (_address.contains("unix://")) {
            loadLibrary();
        }
        INSTANCE = new DeviceManager(DBusConnection.getConnection(_address));
        return INSTANCE;
    }


    /**
     * Get the created instance.
     * @return
     */
    public static DeviceManager getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("Instance not created yet. Please use " + DeviceManager.class.getSimpleName() + ".createInstance() first");
        }

        return INSTANCE;
    }

    /**
     * Search for all bluetooth adapters connected to this machine.
     * Will set the defaultAdapter to the first find adapter if no defaultAdapter was specified before.
     *
     * @return List of adapters, maybe empty, never null
     */
    public List<BluetoothAdapter> scanForBluetoothAdapters() {
        bluetoothAdaptersByAdapterName.clear();
        bluetoothAdaptersByMac.clear();

        Set<String> scanObjectManager = DbusHelper.findNodes(dbusConnection, "/org/bluez");
        for (String hci : scanObjectManager) {
            Adapter1 adapter = DbusHelper.getRemoteObject(dbusConnection, "/org/bluez/" + hci, Adapter1.class);
            if (adapter != null) {
                BluetoothAdapter bt2 = new BluetoothAdapter(adapter, "/org/bluez/" + hci, dbusConnection);
                bluetoothAdaptersByMac.put(bt2.getAddress(), bt2);
                bluetoothAdaptersByAdapterName.put(hci, bt2);
            }
        }

        ArrayList<BluetoothAdapter> adapterList = new ArrayList<>(bluetoothAdaptersByAdapterName.values());

        if (defaultAdapterMac == null && !bluetoothAdaptersByMac.isEmpty()) {
            defaultAdapterMac = new ArrayList<>(bluetoothAdaptersByMac.keySet()).get(0);
        }

        return adapterList;
    }

    /**
     * Scan for bluetooth devices using the default adapter.
     * @param _timeout
     * @return
     */
    public List<BluetoothDevice> scanForBluetoothDevices(int _timeout) {
        return scanForBluetoothDevices(defaultAdapterMac, _timeout);
    }

    /**
     * Scan for Bluetooth devices for on the given adapter.
     * If adapter is null or could not be found, the default adapter is used.
     *
     * @param _adapter adapter to use (either MAC or Dbus-Devicename (e.g. hci0))
     * @param _timeoutMs timeout in milliseconds to scan for devices
     * @return
     */
    public List<BluetoothDevice> scanForBluetoothDevices(String _adapter, int _timeoutMs) {
        BluetoothAdapter adapter = getAdapter(_adapter);
        if (adapter == null) {
            return new ArrayList<>();
        }

        if (adapter.startDiscovery()) {
            try {
                Thread.sleep(_timeoutMs);
            } catch (InterruptedException _ex) {
            }
            adapter.stopDiscovery();

            Set<String> scanObjectManager = DbusHelper.findNodes(dbusConnection, adapter.getDbusPath());

            String adapterMac = adapter.getAddress();

            for (String path : scanObjectManager) {
                String devicePath = "/org/bluez/" + adapter.getDeviceName() + "/" + path;
                Device1 device = DbusHelper.getRemoteObject(dbusConnection, devicePath, Device1.class);
                if (device != null) {
                    BluetoothDevice btDev = new BluetoothDevice(device, adapter, devicePath, dbusConnection);

                    if (bluetoothDeviceByAdapterMac.containsKey(adapterMac)) {
                        bluetoothDeviceByAdapterMac.get(adapterMac).add(btDev);
                    } else {
                        List<BluetoothDevice> list = new ArrayList<>();
                        list.add(btDev);
                        bluetoothDeviceByAdapterMac.put(adapterMac, list);
                    }
                }
            }
        }
        return new ArrayList<>(bluetoothDeviceByAdapterMac.values()).get(0);
    }

    /**
     * Find an adapter by the given identifier (either MAC or device name).
     * Will scan for devices if no default device is given and given ident is also null.
     * Will also scan for devices if the requested device could not be found in device map.
     *
     * @param _ident
     * @return device, maybe null if no device could be found with the given ident
     */
    private BluetoothAdapter getAdapter(String _ident) {
        if (_ident == null && defaultAdapterMac == null) {
            scanForBluetoothAdapters();
        }

        if (_ident == null) {
            _ident = defaultAdapterMac;
        }

        if (bluetoothAdaptersByMac.containsKey(_ident)) {
            return bluetoothAdaptersByMac.get(_ident);
        }
        if (bluetoothAdaptersByAdapterName.containsKey(_ident)) {
            return bluetoothAdaptersByAdapterName.get(_ident);
        }
        // adapter not found by any identification, search for new adapters
        List<BluetoothAdapter> scanForBluetoothAdapters = scanForBluetoothAdapters();
        if (!scanForBluetoothAdapters.isEmpty()) { // there are new candidates, try once more
            if (bluetoothAdaptersByMac.containsKey(_ident)) {
                return bluetoothAdaptersByMac.get(_ident);
            }
            if (bluetoothAdaptersByAdapterName.containsKey(_ident)) {
                return bluetoothAdaptersByAdapterName.get(_ident);
            }

        }
        // no luck, no adapters found which are matching the given identification
        return null;
    }

    /**
     * Returns all found bluetooth adapters.
     * Will query for adapters if {@link #scanForBluetoothAdapters()} was not called before.
     * @return list, maybe empty
     */
    public List<BluetoothAdapter> getAdapters() {
        if (bluetoothAdaptersByMac.isEmpty()) {
            scanForBluetoothAdapters();
        }
        return new ArrayList<>(bluetoothAdaptersByMac.values());
    }

    /**
     * Get all bluetooth devices connected to the defaultAdapter.
     * @return list - maybe empty
     */
    public List<BluetoothDevice> getDevices() {
        return getDevices(defaultAdapterMac);
    }

    /**
     * Get all bluetooth devices connected to the adapter with the given MAC address.
     * @param _adapterMac
     * @return list - maybe empty
     */
    public List<BluetoothDevice> getDevices(String _adapterMac) {
        if (bluetoothDeviceByAdapterMac.isEmpty()) {
            scanForBluetoothDevices(_adapterMac, 5000);
        }
        List<BluetoothDevice> list = bluetoothDeviceByAdapterMac.get(_adapterMac);
        if (list == null) {
            return new ArrayList<>();
        }
        return list;
    }

    public static void main(String[] args) {
//        List<BluetoothAdapter> scanForBluetoothAdapters = BleDeviceManager.getInstance().scanForBluetoothAdapters();
//        System.out.println(scanForBluetoothAdapters);
//        System.out.println(scanForBluetoothAdapters.size());
        try {
            DeviceManager.createInstance(false);
            List<BluetoothDevice> scanForBluetoothDevices = DeviceManager.getInstance().scanForBluetoothDevices(3);
            System.out.println(scanForBluetoothDevices);
            System.out.println(scanForBluetoothDevices.size());

        } catch (DBusException _ex) {
            // TODO Auto-generated catch block
            _ex.printStackTrace();
        } finally {
            DeviceManager.getInstance().closeConnection();
        }
    }
}