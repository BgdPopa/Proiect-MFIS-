package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;

public class EFSMContext implements Cloneable, Serializable {

    private static final long serialVersionUID = -7951703032359100975L;

    @Override
    public EFSMContext clone() {
        try {
            return (EFSMContext) super.clone();
        } catch (CloneNotSupportedException e) {
            return new EFSMContext();
        }
    }

    public EFSMContext getNewCopy() {
        return clone();
    }
}
