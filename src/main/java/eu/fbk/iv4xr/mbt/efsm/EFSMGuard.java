package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;

public abstract class EFSMGuard implements Cloneable, Serializable {

    private static final long serialVersionUID = 986337555034767383L;

    @Override
    public EFSMGuard clone() {
        try {
            return (EFSMGuard) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    public abstract boolean guard(EFSMContext ctx);
}
