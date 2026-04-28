package eu.fbk.iv4xr.mbt.fifo;

import eu.fbk.iv4xr.mbt.efsm.EFSM;

public class FifoRunner {
    public static void main(String[] args) {
        System.out.println("--- PORNIM TESTAREA EVO-MBT ---");
        System.out.println("Initializam modelul FIFO...");

        FifoQueueModel modelBuilder = new FifoQueueModel();
        EFSM model = modelBuilder.buildModel();

        System.out.println("Modelul a fost creat cu succes!");
        System.out.println("Starea initiala a cozii este: " + model.getInitialConfiguration().getState().getId());
        System.out.println("-------------------------------");
    }
}
