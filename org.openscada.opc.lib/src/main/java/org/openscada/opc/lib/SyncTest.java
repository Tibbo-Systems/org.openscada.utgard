package org.openscada.opc.lib;

import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.common.JISystem;
import org.jinterop.dcom.core.JISession;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.DataCallback;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;

public class SyncTest {

    public static void main(String[] args) {
        JISystem.getLogger().setLevel(Level.OFF);

        ConnectionInformation connectionInformation = new ConnectionInformation();
        connectionInformation.setHost("localhost");
        connectionInformation.setDomain("");
        connectionInformation.setUser("OPCUser");
        connectionInformation.setPassword("opc");
        connectionInformation.setClsid("F8582CF2-88FB-11D0-B850-00C0F0104305");

        final String groupName = "Group0";
        final String itemId = "Random.Int1";
        final Server server = new Server(connectionInformation, Executors.newSingleThreadScheduledExecutor());

        try {
            JISession session = JISession.createSession ( connectionInformation.getDomain (), connectionInformation.getUser (), connectionInformation.getPassword () );
            session.useSessionSecurity(true);
            session.useNTLMv2(true);

            server.dispose();
            server.disconnect();
            server.connect(session);
            System.out.println("-----status: " + server.getServerState());

            server.addGroup(groupName);

            final AccessBase ab = new SyncAccess(server, 1000);
            System.out.println("-----binding");
            ab.bind();
            System.out.println("-----bind");
            ab.addItem(itemId, (item, itemState) -> {
                try {
                    System.out.println("-----data: " + Integer.valueOf((Character) itemState.getValue().getObject()) );
                } catch (JIException e) {
                    System.out.println("-----error: " + e);
                }

            });
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

}
