package Libraries;

import jade.core.Agent;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestTransportLibrary implements ITransport {

    private Agent myAgent;
    private coppelia.remoteApi sim;
    private int clientID = -1;
    private int robotHandle = -1;

    // Coordenadas baseadas no teu cenário do CoppeliaSim
    private final Map<String, float[]> locations = new HashMap<String, float[]>() {{
        // Posições X, Y, Z baseadas no cenário do Lab1
        put("Source", new float[]{0.050f, -1.826f, 0.10f});
        put("GlueStation1", new float[]{-0.525f, -1.225f, 0.10f});
        put("GlueStation2", new float[]{-0.525f, 1.100f, 0.10f});
        put("QualityControlStation1", new float[]{0.725f, -1.225f, 0.10f});
        put("QualityControlStation2", new float[]{0.750f, 1.100f, 0.10f});
    }};
    @Override
    public void init(Agent a) {
        this.myAgent = a;
        sim = new coppelia.remoteApi();

        // Porta 19997 confirmada no teu ficheiro remoteApiConnections.txt
        clientID = sim.simxStart("127.0.0.1", 19997, true, true, 5000, 5);

        if (clientID != -1) {
            System.out.println("[COPPELIA] " + myAgent.getLocalName() + " conectado com sucesso!");

            // Usamos o nome completo da classe para evitar o erro de 'cannot be applied'
            coppelia.IntW handle = new coppelia.IntW(0);
            int res = sim.simxGetObjectHandle(clientID, "youBot", handle, coppelia.remoteApi.simx_opmode_blocking);

            if (res == coppelia.remoteApi.simx_return_ok) {
                this.robotHandle = handle.getValue();
                System.out.println("[TS] youBot Handle encontrado: " + robotHandle);
            }
        } else {
            System.err.println("[COPPELIA] Erro na ligação. Verifica se o simulador está em PLAY.");
        }
    }

    @Override
    public boolean executeMove(String origin, String destination, String productID) {
        System.out.println("[TS] A mover de " + origin + " para " + destination);

        float[] coords = locations.get(destination);

        if (clientID != -1 && robotHandle != -1 && coords != null) {
            // Criar o objeto FloatWA do package coppelia
            coppelia.FloatWA pos = new coppelia.FloatWA(3);
            // Se .getArray() der erro, tenta pos.v[0] = coords[0];
            pos.getArray()[0] = coords[0];
            pos.getArray()[1] = coords[1];
            pos.getArray()[2] = coords[2];

            sim.simxSetObjectPosition(clientID, robotHandle, -1, pos, coppelia.remoteApi.simx_opmode_blocking);

        }

        try {
            Thread.sleep(5000);
        } catch (InterruptedException ex) {
            Logger.getLogger(TestTransportLibrary.class.getName()).log(Level.SEVERE, null, ex);
        }
        return true;
    }

    @Override
    public String[] getSkills() {
        return new String[]{Utilities.Constants.SK_MOVE};
    }
}