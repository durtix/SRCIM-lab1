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
        public ResourceResponder(Agent a, MessageTemplate mt) {
            super(a, mt);
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) {
            ACLMessage reply = cfp.createReply();

            if (isBusy) {
                // Se estiver a trabalhar, recusa para o produto tentar outro agente (ex: GS1 -> GS2)
                reply.setPerformative(ACLMessage.REFUSE);
                System.out.println("[" + id + "] Ocupado! Recusei pedido de " + cfp.getSender().getLocalName());
            } else {
                // Se estiver livre, envia proposta
                reply.setPerformative(ACLMessage.PROPOSE);
                // Métrica aleatória para o produto escolher o "melhor"
                reply.setContent(String.valueOf(new Random().nextDouble()));
            }
            return reply;
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) {
            // BLOQUEIA o recurso
            isBusy = true;

            System.out.println("[" + id + "] A executar skill: " + cfp.getContent() + " para " + cfp.getSender().getLocalName());

            // Execução física (síncrona na biblioteca)
            myLib.executeSkill(cfp.getContent());

            ACLMessage inform = accept.createReply();
            inform.setPerformative(ACLMessage.INFORM);
            inform.setContent(location);

            // LIBERTA o recurso após terminar
            isBusy = false;
            return inform;
        }
    }
}