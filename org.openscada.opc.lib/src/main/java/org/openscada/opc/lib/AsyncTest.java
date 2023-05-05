package org.openscada.opc.lib;

import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.common.JISystem;
import org.jinterop.dcom.core.JISession;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.common.NotConnectedException;
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.Async20Access;
import org.openscada.opc.lib.da.DuplicateGroupException;
import org.openscada.opc.lib.da.Server;

public class AsyncTest {

    public static void main(String[] args) {
        /**
         * In order to set up COM security permissions, download jna and jna-platfrom jar-s and add to the CLASSPATH
         * https://github.com/java-native-access/jna#jna. Then uncomment lines below.
         */
//        WinNT.HRESULT hResult = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
//        System.out.println("CoInitializeEx: " + hResult.toString());
//        hResult = Ole32.INSTANCE.CoInitializeSecurity(null, -1, null, null,
//                Ole32.RPC_C_AUTHN_LEVEL_DEFAULT, Ole32.RPC_C_IMP_LEVEL_IMPERSONATE, null, 0, null);
//        System.out.println("CoInitializeSecurity: " + hResult.toString());

        JISystem.getLogger().setLevel(Level.OFF);
        ConnectionInformation connectionInformation = new ConnectionInformation();
        connectionInformation.setHost("127.0.0.1");
        connectionInformation.setDomain("");
        connectionInformation.setUser("Admin");
        connectionInformation.setPassword("admin");
        connectionInformation.setClsid("F8582CF2-88FB-11D0-B850-00C0F0104305");

        final String groupName = "Group0";
        final String itemId = "Random.Int1";
        final Server server = new Server(connectionInformation, Executors.newSingleThreadScheduledExecutor());

        try {
            JISession session = JISession.createSession ( connectionInformation.getDomain (), connectionInformation.getUser (), connectionInformation.getPassword () );
            session.useSessionSecurity(true);
            session.useNTLMv2(true);

            server.dispose();
            server.connect(session);
            System.out.println("-----status: " + server.getServerState());

            server.addGroup(groupName);

            final AccessBase ab = new CustomAsync20Access(server, 1000, false);
            ab.addItem(itemId, (item, itemState) -> {
                try {
                    System.out.println("-----data: " + Integer.valueOf((Character) itemState.getValue().getObject()) );
                } catch (JIException e) {
                    System.out.println("-----error: " + e);
                }

            });
            System.out.println("-----binding");
            ab.bind();
            System.out.println("-----bind");
            Thread.sleep(10 * 1000);
            System.out.println("-----unbinding");
            ab.unbind();
            System.out.println("-----unbind");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            server.dispose();
            server.disconnect();
        }
    }

    static public class CustomAsync20Access extends Async20Access {
        CustomAsync20Access(final Server server, final int period, final boolean initialRefresh) throws IllegalArgumentException, UnknownHostException, NotConnectedException, JIException, DuplicateGroupException {
            super(server, period, initialRefresh);
        }

        @Override
        public void connectionStateChanged (boolean connected) {
            try {
                if (connected) {
                    start ();
                } else {
                    stop ();
                }
            } catch ( final Exception e ) {
                System.err.println(String.format( "Failed to change state (%s)", connected));
                e.printStackTrace();
            }
        }
    }
}
