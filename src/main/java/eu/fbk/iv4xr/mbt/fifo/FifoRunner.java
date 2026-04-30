package eu.fbk.iv4xr.mbt.fifo;

import eu.fbk.iv4xr.mbt.efsm.EFSM;

/**
 * Runner minimal pentru demo:
 * - construieste modelul FIFO;
 * - afiseaza starea initiala, util in prezentare pentru a valida initializarea.
 */
public class FifoRunner {

    /**
     * Punct de intrare pentru demonstratia rapida a modelului.
     */
    public static void main(String[] args) {
        System.out.println("--- PORNIM TESTAREA EVO-MBT ---");
        System.out.println("Initializam modelul FIFO...");

        // Construim modelul EFSM al cozii FIFO.
        FifoQueueModel modelBuilder = new FifoQueueModel();
        EFSM model = modelBuilder.buildModel();

        // Mesaje utile pentru demo rapid in prezentare.
        System.out.println("Modelul a fost creat cu succes!");
        System.out.println("Starea initiala a cozii este: " + model.getInitialConfiguration().getState().getId());
        System.out.println("-------------------------------");
    }
}
