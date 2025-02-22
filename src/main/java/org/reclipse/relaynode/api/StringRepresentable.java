package org.reclipse.relaynode.api;

public interface StringRepresentable<T> {
	String toString();
	static StringRepresentable<Object> fromString(String string) {
		throw new UnsupportedOperationException("fromString not implemented");
	};
}
