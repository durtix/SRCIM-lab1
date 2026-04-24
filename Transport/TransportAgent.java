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

public class TransportAgent extends Agent {

    String id;
    ITransport myLib;
    String description;

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

        // Registo no DF
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
            // Filtra apenas REQUEST com ontologia ONTOLOGY_MOVE
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchOntology(Constants.ONTOLOGY_MOVE)
            );
            ACLMessage msg = receive(mt);

            if (msg != null) {
                String content   = msg.getContent();
                String productID = msg.getSender().getLocalName();

                // Parse: "origem#TOKEN#destino"
                StringTokenizer st = new StringTokenizer(content, Constants.TOKEN);
                String origin      = st.nextToken().trim();
                String dest        = st.nextToken().trim();

                System.out.println("[" + id + "] A mover " + productID
                        + " de " + origin + " para " + dest);

                // FIX: Envia AGREE imediatamente antes de executar
                // O ProductAgent fica à espera deste AGREE no passo 1 do TransportBehaviour
                ACLMessage agree = msg.createReply();
                agree.setPerformative(ACLMessage.AGREE);
                send(agree);


                new Thread(() -> {
                    boolean success = myLib.executeMove(origin, dest, productID);

                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(success ? ACLMessage.INFORM : ACLMessage.FAILURE);
                    reply.setContent(dest);
                    send(reply);

                    System.out.println("[" + id + "] Transporte concluído: " + origin + " → " + dest);
                }).start();



            } else {
                block();
            }
        }
    }
}