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
    private int vagas = 2;
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
                jade.lang.acl.MessageTemplate mtRelease = jade.lang.acl.MessageTemplate.MatchContent("RELEASE");
                jade.lang.acl.MessageTemplate mtExec = jade.lang.acl.MessageTemplate.MatchOntology(Utilities.Constants.ONTOLOGY_EXECUTE_SKILL);
                jade.lang.acl.MessageTemplate mt = jade.lang.acl.MessageTemplate.or(mtRelease, mtExec);

                jade.lang.acl.ACLMessage msg = receive(mt);
                if (msg != null) {
                    if (msg.getContent().equals("RELEASE")) {
                        vagas++;
                        isBusy = false;
                        currentProduct = null;
                    } else if (msg.getPerformative() == jade.lang.acl.ACLMessage.REQUEST) {
                        isBusy = true;
                        System.out.println("[" + id + "] A preparar a mesa para a skill: " + msg.getContent());

                        try {
                            if (myLib instanceof Libraries.SimResourceLibrary) {
                                Libraries.SimResourceLibrary lib = (Libraries.SimResourceLibrary) myLib;

                                // 1. Força a escrita do sinal limpo
                                lib.sim.simxSetStringSignal(lib.clientID, getLocalName(), new coppelia.CharWA(""), lib.sim.simx_opmode_blocking);
                                lib.sim.simxSetIntegerSignal(lib.clientID, getLocalName(), 0, lib.sim.simx_opmode_blocking);

                                // 2. A GARANTIA: Fica em loop até o CoppeliaSim confirmar que o sinal desceu mesmo para 0
                                coppelia.IntW check = new coppelia.IntW(-1);
                                long startCheck = System.currentTimeMillis();
                                while (check.getValue() != 0 && (System.currentTimeMillis() - startCheck < 3000)) {
                                    lib.sim.simxGetIntegerSignal(lib.clientID, getLocalName(), check, lib.sim.simx_opmode_blocking);
                                    Thread.sleep(50);
                                }
                            }

                            // 3. Dá 1.5s para a peça "cair" fisicamente e estabilizar no sensor da máquina
                            Thread.sleep(1500);
                        } catch (Exception e) {}

                        // Agora é matematicamente impossível o Java saltar a skill!
                        System.out.println("[" + id + "] antes de mandar exect!");
                        myLib.executeSkill(msg.getContent());
                        System.out.println("[" + id + "] Skill física concluída no simulador!");

                        // 4. Limpeza no final para não deixar lixo para o próximo produto
                        try {
                            if (myLib instanceof Libraries.SimResourceLibrary) {
                                Libraries.SimResourceLibrary lib = (Libraries.SimResourceLibrary) myLib;
                                lib.sim.simxSetStringSignal(lib.clientID, getLocalName(), new coppelia.CharWA(""), lib.sim.simx_opmode_blocking);
                                lib.sim.simxSetIntegerSignal(lib.clientID, getLocalName(), 0, lib.sim.simx_opmode_blocking);
                            }
                        } catch (Exception e) {}
                        isBusy = false;

                        jade.lang.acl.ACLMessage reply = msg.createReply();
                        reply.setPerformative(jade.lang.acl.ACLMessage.INFORM);
                        send(reply);
                    }
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

            // Se o braço estiver a colar OU se não houver vagas (0), recusa!
            if (isBusy || vagas <= 0) {
                reply.setPerformative(ACLMessage.REFUSE);
            } else {
                vagas--; // RESERVA A VAGA IMEDIATAMENTE!
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(String.valueOf(new Random().nextDouble()));
            }
            return reply;
        }

        @Override
        protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
            vagas++; // Se o produto escolheu outra máquina, devolvemos a vaga
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) {
            ACLMessage inform = accept.createReply();
            inform.setPerformative(ACLMessage.INFORM);
            inform.setContent(location);
            return inform; // Não mexe nas vagas aqui, já foram reservadas no Cfp
        }
    }
}