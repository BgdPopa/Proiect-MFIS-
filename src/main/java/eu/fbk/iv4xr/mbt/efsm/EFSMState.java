package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;

public class EFSMState implements Comparable<EFSMState>, Cloneable, Serializable {

    private static final long serialVersionUID = 3837571448310382982L;
    private final String id;

    public EFSMState(String id) {
        if (id == null || id.isEmpty()) {
            throw new RuntimeException("EFSMState id cannot be empty string or null");
        }
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EFSMState)) return false;
        EFSMState other = (EFSMState) obj;
        return other.getId().equals(id);
    }

    @Override
    public int compareTo(EFSMState o) {
        return this.getId().compareTo(o.getId());
    }

    @Override
    public EFSMState clone() {
        return new EFSMState(this.id);
    }

    @Override
    public String toString() {
        return id;
    }
}
