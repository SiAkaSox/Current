package io.ruin.model.item.containers.collectionlog;

import io.ruin.model.item.Item;

import java.util.Map;

public class CollectionLogItem extends Item {

    public CollectionLogItem(int id, int amount, Map<String, String> attributes) {
        super(id, amount, attributes);
    }

    @Override
    public CollectionLogItem copy() {
        return new CollectionLogItem(getId(), getAmount(), copyOfAttributes());
    }

}