package Product;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetInitiator;
import Utilities.Constants;

import java.util.ArrayList;
import java.util.Vector;

public class ProductAgent extends Agent {

    String id;
    ArrayList<String> executionPlan = new ArrayList<>();

    String currentLocation = "Source";
    int currentSkillIndex  = 0;

    AID    previousResourceAID    = null;
    AID    chosenResourceAID      = null;
    String chosenResourceLocation = null;

    @Override
    protected void setup() {
        System.out.println(">>>> [DEBUG] O AGENTE " + this.getLocalName() + " ACORDOU!");
        Object[] args = getArguments();

        if (args != null && args.length > 0) {
            System.out.println(">>> AGENTE: "       + args[0]);
            System.out.println(">>> TIPO RECEBIDO: " + args[1]);

            this.id = (String) args[0];
            this.executionPlan = this.getExecutionList((String) args[1]);

            if (this.executionPlan == null) {
                System.out.println("!!! ERRO: Plano nulo para tipo " + args[1]);
            } else {
                System.out.println(">>> PLANO CARREGADO: " + this.executionPlan);
                startProduction();
            }
        }
    }

    private void startProduction() {
        if (currentSkillIndex < executionPlan.size()) {
            String skillNeeded = executionPlan.get(currentSkillIndex);

            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            cfp.setProtocol(jade.domain.FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
            cfp.setOntology(Constants.ONTOLOGY_NEGOTIATE_RESOURCE);
            cfp.setContent(skillNeeded);

            addBehaviour(new NegotiateBehaviour(this, cfp));
        } else {
            System.out.println("[" + id + "] Plano concluído com sucesso em: " + currentLocation);
            if (chosenResourceAID != null) {
                ACLMessage releaseMsg = new ACLMessage(ACLMessage.INFORM);
                releaseMsg.addReceiver(chosenResourceAID);
                releaseMsg.setContent("RELEASE");
                send(releaseMsg);
            }

            doDelete();
        }
    }

    // =========================================================================
    // NegotiateBehaviour — ContractNet
    // =========================================================================
    private class NegotiateBehaviour extends ContractNetInitiator {

        public NegotiateBehaviour(Agent a, ACLMessage cfp) { super(a, cfp); }

        @Override
        protected Vector prepareCfps(ACLMessage cfp) {
            Vector v = new Vector();
            String skillNeeded = cfp.getContent();

            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(Constants.DFSERVICE_RESOURCE);
            sd.setName(skillNeeded);
            template.addServices(sd);

            try {
                DFAgentDescription[] results = DFService.search(myAgent, template);
                for (DFAgentDescription dfd : results) {
                    cfp.addReceiver(dfd.getName());
                }
            } catch (FIPAException e) { e.printStackTrace(); }

            v.add(cfp);
            return v;
        }

        @Override
        protected void handleAllResponses(Vector responses, Vector acceptances) {
            ACLMessage bestPropose = null;
            double bestMetric = Double.MAX_VALUE;

            for (Object obj : responses) {
                ACLMessage res = (ACLMessage) obj;
                if (res.getPerformative() == ACLMessage.PROPOSE) {
                    try {
                        double metric = Double.parseDouble(res.getContent());
                        if (metric < bestMetric) {
                            bestMetric  = metric;
                            bestPropose = res;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (bestPropose != null) {
                ACLMessage accept = bestPropose.createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                acceptances.add(accept);

                previousResourceAID = chosenResourceAID;

                chosenResourceAID = bestPropose.getSender();
                System.out.println("[" + id + "] Escolhi: " + chosenResourceAID.getLocalName());
            }else {
                // ---> NOVO CÓDIGO: Se ninguém aceitar, espera 2 segundos e tenta de novo
                System.out.println("[" + id + "] Todas as máquinas ocupadas. A aguardar...");
                myAgent.addBehaviour(new jade.core.behaviours.WakerBehaviour(myAgent, 5000) {
                    @Override
                    protected void onWake() {
                        startProduction(); // Tenta a mesma skill outra vez
                    }
                });
            }

            // Envia REJECT a todos os outros
            for (Object obj : responses) {
                ACLMessage res = (ACLMessage) obj;
                if (res.getPerformative() == ACLMessage.PROPOSE && res != bestPropose) {
                    ACLMessage reject = res.createReply();
                    reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    acceptances.add(reject);
                }
            }
        }

        @Override
        protected void handleInform(ACLMessage inform) {
            chosenResourceLocation = inform.getContent();
            System.out.println("[" + id + "] Skill executada. Destino era: " + chosenResourceLocation);
            addBehaviour(new TransportBehaviour());
        }

        @Override
        protected void handleFailure(ACLMessage failure) {
            System.out.println("[" + id + "] FAILURE da negociação: " + failure.getContent());
        }
    }

    // =========================================================================
    // TransportBehaviour — pedido de movimento ao AGV
    // =========================================================================
    // FIX PRINCIPAL: usa steps não-bloqueantes em vez de blockingReceive.
    //
    // blockingReceive() bloqueia a thread do agente inteiro — todos os outros
    // behaviours param enquanto este espera. Com steps + block() o JADE
    // pode continuar a processar outros agentes enquanto aguardamos resposta.
    //
    // Protocolo:
    //   Passo 0: sem transporte necessário → avança directamente
    //            com transporte → envia REQUEST e vai para passo 1
    //   Passo 1: aguarda AGREE do TA (confirma que recebeu)
    //   Passo 2: aguarda INFORM do TA (movimento concluído) → avança
    // =========================================================================
    private class TransportBehaviour extends SimpleBehaviour {

        private boolean finished = false;
        private int     step     = 0;
        private AID     taAID    = null;

        @Override
        public void onStart() {
            // Se já estamos no local certo, não precisamos de transporte
            if (currentLocation.equals(chosenResourceLocation)) {
                System.out.println("[" + id + "] Já estou no local. A avançar...");
                finished = true;
                currentSkillIndex++;
                startProduction();
            }
            // Se precisamos de mover, arranca no passo 0
        }

        @Override
        public void action() {
            if (finished) return;

            switch (step) {

                case 0: {
                    // Encontra o TA no DF e envia REQUEST
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType(Constants.DFSERVICE_TRANSPORT);
                    sd.setName(Constants.SK_MOVE);
                    template.addServices(sd);

                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template);
                        if (result.length == 0) {
                            System.out.println("[" + id + "] TransportAgent não encontrado!");
                            finished = true;
                            return;
                        }
                        taAID = result[0].getName();
                    } catch (FIPAException e) {
                        e.printStackTrace();
                        finished = true;
                        return;
                    }

                    ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
                    msg.addReceiver(taAID);
                    msg.setOntology(Constants.ONTOLOGY_MOVE);
                    msg.setContent(currentLocation + Constants.TOKEN + chosenResourceLocation);
                    send(msg);

                    System.out.println("[" + id + "] REQUEST transporte: "
                            + currentLocation + " → " + chosenResourceLocation);
                    step = 1;
                    break;
                }

                case 1: {
                    // Aguarda AGREE do TA — sem bloquear outros behaviours
                    MessageTemplate mt = MessageTemplate.and(
                            MessageTemplate.MatchPerformative(ACLMessage.AGREE),
                            MessageTemplate.MatchSender(taAID)
                    );
                    ACLMessage agree = receive(mt);
                    if (agree != null) {
                        System.out.println("[" + id + "] AGV em movimento...");
                        step = 2;
                    } else {
                        block(); // cede o controlo até chegar uma mensagem
                    }
                    break;
                }

                case 2: {
                    // Aguarda INFORM do TA — sem bloquear outros behaviours
                    MessageTemplate mt = MessageTemplate.and(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchSender(taAID)
                    );
                    ACLMessage inform = receive(mt);
                    if (inform != null) {
                        if (previousResourceAID != null) {
                            ACLMessage releaseMsg = new ACLMessage(ACLMessage.INFORM);
                            releaseMsg.addReceiver(previousResourceAID);
                            releaseMsg.setContent("RELEASE");
                            send(releaseMsg);
                            previousResourceAID = null; // Limpa a memória
                        }

                        currentLocation = chosenResourceLocation;
                        System.out.println("[" + id + "] Movido para: " + currentLocation);
                        finished = true;
                        currentSkillIndex++;
                        startProduction(); // Avança para a próxima skill
                    } else {
                        block();
                    }
                    break;
                }
            }
        }

        @Override
        public boolean done() { return finished; }
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }

    private ArrayList<String> getExecutionList(String productType) {
        switch (productType) {
            case "A": return new ArrayList<>(Utilities.Constants.PROD_A);
            case "B": return new ArrayList<>(Utilities.Constants.PROD_B);
            case "C": return new ArrayList<>(Utilities.Constants.PROD_C);
        }
        return null;
    }
}