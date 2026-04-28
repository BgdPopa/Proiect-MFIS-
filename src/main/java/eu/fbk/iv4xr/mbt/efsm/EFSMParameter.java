package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;

public class EFSMParameter implements Cloneable, Serializable {

    private static final long serialVersionUID = -6839254675772433933L;

    @Override
    public EFSMParameter clone() {
        try {
            return (EFSMParameter) super.clone();
        } catch (CloneNotSupportedException e) {
            return new EFSMParameter();
        }
    }
}
