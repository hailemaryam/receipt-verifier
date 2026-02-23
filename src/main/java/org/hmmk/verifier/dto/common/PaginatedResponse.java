package org.hmmk.verifier.dto.common;

import java.util.List;

public class PaginatedResponse<T> {
    public List<T> items;
    public long total;
    public int page;
    public int size;

    public PaginatedResponse() {
    }

    public PaginatedResponse(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
