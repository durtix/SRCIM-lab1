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
        // Cria uma lista misturada (ex: A, B, C, A, B, C...)
        java.util.ArrayList<String> orderList = new java.util.ArrayList<>();
        int max = Math.max(productA, Math.max(productB, productC));
        for (int i = 0; i < max; i++) {
            if (i < productA) orderList.add("A");
            if (i < productB) orderList.add("B");
            if (i < productC) orderList.add("C");
        }

// Lança os produtos a cada 20 segundos sem bloquear o JADE
        addBehaviour(new jade.core.behaviours.TickerBehaviour(this, 20000) {
            int index = 0;
            @Override
            protected void onTick() {
                if (index < orderList.size()) {
                    try {
                        launchProduct(orderList.get(index));
                    } catch (StaleProxyException e) { e.printStackTrace(); }
                    index++;
                } else {
                    stop(); // Terminou a encomenda
                }
            }
        });
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