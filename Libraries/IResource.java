package Libraries;

import jade.core.Agent;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public interface IResource {
    public void init(Agent myAgent); 
    public boolean launchProduct(String productID);
    public boolean finishProduct(String productID);
    public String[] getSkills();
    public boolean executeSkill(String skillID);
}
