package Resource;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetResponder;
import Libraries.IResource;
import Utilities.Constants;
import java.util.Arrays;
import java.util.Random;

public class ResourceAgent extends Agent {

    private boolean isBusy = false; // Estado do recurso
    private jade.core.AID currentProduct = null;
    String id;
    IResource myLib;
    String description;
    String[] associatedSkills;
    String location;

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.id = (String) args[0];
        this.description = (String) args[1];

        // Inicialização da biblioteca
        try {
            String className = "Libraries." + (String) args[2];
            myLib = (IResource) Class.forName(className).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.location = (String) args[3];
        myLib.init(this);
        this.associatedSkills = myLib.getSkills();

        System.out.println("Resource Deployed: " + this.id + " Executes: " + Arrays.toString(associatedSkills));

        registerInDF();

        // Adiciona o responder para o protocolo Contract Net
        addBehaviour(new ResourceResponder(this, MessageTemplate.MatchProtocol(jade.domain.FIPANames.InteractionProtocol.FIPA_CONTRACT_NET)));

        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            @Override
            public void action() {
                jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.MatchContent("RELEASE");
                jade.lang.acl.ACLMessage msg = receive(mt);
                if (msg != null) {
                    isBusy = false;
                    currentProduct = null;// A mesa destranca fisicamente
                    System.out.println("[" + getLocalName() + "] Peça recolhida. Mesa livre!");
                } else {
                    block();
                }
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        for (String skill : associatedSkills) {
            ServiceDescription sd = new ServiceDescription();
            sd.setName(skill);
            sd.setType(Constants.DFSERVICE_RESOURCE);
            dfd.addServices(sd);
        }
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private class ResourceResponder extends ContractNetResponder {
        private boolean isReserved = false; // Nova variável de tranca rápida

        public ResourceResponder(Agent a, MessageTemplate mt) {
            super(a, mt);
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) {
            ACLMessage reply = cfp.createReply();

            boolean lockedByOther = isBusy && (currentProduct == null || !currentProduct.equals(cfp.getSender()));
            // Rejeita se estiver a trabalhar OU se já tiver prometido a outro
            if (lockedByOther || isReserved) {
                reply.setPerformative(ACLMessage.REFUSE);
            } else {
                isReserved = true; // Tranca imediatamente mal faz a proposta!
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(String.valueOf(new Random().nextDouble()));
            }
            return reply;
        }

        @Override
        protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
            // Se o produto não aceitar esta máquina, destranca a reserva
            isReserved = false;
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) {
            isBusy = true;      // Passa a estar oficialmente ocupada
            isReserved = false;
            currentProduct = cfp.getSender();

            System.out.println("[" + id + "] A executar skill: " + cfp.getContent() + " para " + cfp.getSender().getLocalName());

            myLib.executeSkill(cfp.getContent());

            ACLMessage inform = accept.createReply();
            inform.setPerformative(ACLMessage.INFORM);
            inform.setContent(location);

            // Continua sem o isBusy = false aqui! Só liberta com o RELEASE.
            return inform;
        }
    }
}