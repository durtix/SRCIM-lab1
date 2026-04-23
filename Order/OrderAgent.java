package Order;

import Product.ProductAgent;
import jade.core.Agent;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

public class OrderAgent extends Agent {

    int productA;
    int productB;
    int productC;

    private static int productCounter = 0;

    @Override
    protected void setup() {
        Object[] args = this.getArguments();
        this.productA = (int) args[0];
        this.productB = (int) args[1];
        this.productC = (int) args[2];


        System.out.println("Order Received " + " ProductsA " + productA +
                " ProductsB " + productB + " ProductsC " + productC);

        // AQUI: Ciclo para lançar os produtos conforme as quantidades da GUI
        try {
            for (int i = 0; i < productA; i++) {
                launchProduct("A");
                Thread.sleep(20000);
            }
            for (int i = 0; i < productB; i++) {
                launchProduct("B");
                Thread.sleep(20000);
            }
            for (int i = 0; i < productC; i++) {
                launchProduct("C");
                Thread.sleep(20000);
            }
        } catch (StaleProxyException | InterruptedException e) {
            System.out.println("[ERRO] Falha ao criar agentes de produto.");
            e.printStackTrace();
        }
    }

    @Override
    protected void takeDown() {
        super.takeDown();
    }

    private void launchProduct(String productType) throws StaleProxyException {
        // Gera um ID único para cada agente (ex: Product0, Product1...)
        String id = "Product" + this.productCounter;

        // Usamos createNewAgent para garantir que o JADE encontra a classe no package Product
        AgentController agent = this.getContainerController().createNewAgent(
                id,
                "Product.ProductAgent",
                new Object[]{id, productType}
        );

        agent.start();
        this.productCounter++;
    }
}