package com.omwh.utils;

import java.util.List;

final class EntityOwnership {
    private EntityOwnership() { }

    static <T> boolean isExternal(List<T> captured, T candidate) {
        for (T entity : captured) {
            if (entity == candidate) return false;
        }
        return true;
    }
}
