package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;

public abstract class EFSMOperation implements Cloneable, Serializable {

    private static final long serialVersionUID = 503965196716794551L;

    public EFSMOperation() {
    }

    @Override
    public EFSMOperation clone() {
        return new EFSMOperation() {
            @Override
            public boolean execute(EFSMContext ctx) {
                return false;
            }
        };
    }

    public abstract boolean execute(EFSMContext ctx);
}
