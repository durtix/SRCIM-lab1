package Transport;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import Libraries.ITransport;
import Utilities.Constants;

import java.util.StringTokenizer;
import java.util.LinkedList;

public class TransportAgent extends Agent {

    String id;
    ITransport myLib;
    String description;
    private volatile boolean agvBusy = false;
    private volatile int productsInGS = 0;
    private LinkedList<ACLMessage> waitingQueue = new LinkedList<>();
    private LinkedList<ACLMessage> priorityQueue = new LinkedList<>();
    private final java.util.concurrent.ExecutorService moverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id          = (String) args[0];
        this.description = (String) args[1];

        try {
            String className = "Libraries." + (String) args[2];
            myLib = (ITransport) Class.forName(className).newInstance();
        } catch (Exception e) { e.printStackTrace(); }

        myLib.init(this);
        System.out.println("Transport Deployed: " + this.id);

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(Constants.DFSERVICE_TRANSPORT);
        sd.setName(Constants.SK_MOVE);
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }

        addBehaviour(new TransportResponder());
    }

    private class TransportResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchOntology(Constants.ONTOLOGY_MOVE)
            );
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String content = msg.getContent();
                StringTokenizer st = new StringTokenizer(content, Constants.TOKEN);
                String origin = st.nextToken().trim();
                String dest = st.nextToken().trim();

                boolean isSaida = dest.toLowerCase().contains("qualitycontrol") ||
                        dest.toLowerCase().contains("sink");

                if (isSaida) {
                    if (agvBusy) {
                        priorityQueue.add(msg);
                        System.out.println("[" + id + "] Saída prioritária em espera (AGV ocupado): " + msg.getSender().getLocalName());
                    } else {
                        executeTransportProcess(msg, origin, dest);
                    }
                } else if (agvBusy || (origin.equalsIgnoreCase("Source") && productsInGS >= 2)) {
                    waitingQueue.add(msg);
                    System.out.println("[" + id + "] AGV ocupado ou GS cheias (" + productsInGS + "). Pedido de " + msg.getSender().getLocalName() + " em espera.");
                } else {
                    executeTransportProcess(msg, origin, dest);
                }
            } else {
                block();
            }
        }
    }

    private void executeTransportProcess(ACLMessage msg, String origin, String dest) {
        String productID = msg.getSender().getLocalName();
        agvBusy = true;

        if (origin.toLowerCase().contains("gluestation")) productsInGS--;

        System.out.println("[" + id + "] A mover " + productID + " de " + origin + " para " + dest + " (GS Ativas: " + productsInGS + ")");

        ACLMessage agree = msg.createReply();
        agree.setPerformative(ACLMessage.AGREE);
        send(agree);

        Agent agentRef = this;

        moverExecutor.submit(() -> {
            boolean success = myLib.executeMove(origin, dest, productID);

            agentRef.addBehaviour(new jade.core.behaviours.OneShotBehaviour() {
                @Override
                public void action() {
                    if (success && dest.toLowerCase().contains("gluestation")) productsInGS++;

                    if (success && origin.toLowerCase().contains("gluestation")) {
                        jade.core.AID gsAID = new jade.core.AID(origin, jade.core.AID.ISLOCALNAME);
                        ACLMessage release = new ACLMessage(ACLMessage.INFORM);
                        release.addReceiver(gsAID);
                        release.setContent("RELEASE");
                        send(release);
                    }

                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(success ? ACLMessage.INFORM : ACLMessage.FAILURE);
                    reply.setContent(dest);
                    send(reply);

                    agvBusy = false;

                    // Drena primeiro a fila de prioridade, depois a normal
                    if (!priorityQueue.isEmpty()) {
                        ACLMessage nextMsg = priorityQueue.poll();
                        StringTokenizer st = new StringTokenizer(nextMsg.getContent(), Constants.TOKEN);
                        if (st.countTokens() >= 2) {
                            executeTransportProcess(nextMsg, st.nextToken().trim(), st.nextToken().trim());
                        }
                    } else if (!waitingQueue.isEmpty()) {
                        ACLMessage nextMsg = waitingQueue.poll();
                        StringTokenizer st = new StringTokenizer(nextMsg.getContent(), Constants.TOKEN);
                        if (st.countTokens() >= 2) {
                            executeTransportProcess(nextMsg, st.nextToken().trim(), st.nextToken().trim());
                        }
                    }
                }
            });
        });
    }
}