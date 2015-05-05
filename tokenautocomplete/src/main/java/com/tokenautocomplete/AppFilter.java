package com.tokenautocomplete;

import android.widget.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Class for filtering Adapter, relies on keepObject in FilteredArrayAdapter
 * <p/>
 * based on gist by Tobias Schurg
 * in turn inspired by inspired by Alxandr
 * (http://stackoverflow.com/a/2726348/570168)
 */
class AppFilter<T> extends Filter {

    private FilteredArrayAdapter<T> filteredArrayAdapter;
    private List<T> sourceObjects;

    public AppFilter(FilteredArrayAdapter<T> filteredArrayAdapter, List<T> objects) {
        this.filteredArrayAdapter = filteredArrayAdapter;
        setSourceObjects(objects);
    }

    public void setSourceObjects(List<T> objects) {
        synchronized (this) {
            sourceObjects = new ArrayList<>(objects);
        }
    }

    @Override
    protected FilterResults performFiltering(CharSequence chars) {
        FilterResults result = new FilterResults();
        if (chars != null && chars.length() > 0) {
            String mask = chars.toString();
            List<T> keptObjects = new ArrayList<>();

            for (T object : sourceObjects) {
                if (filteredArrayAdapter.keepObject(object, mask))
                    keptObjects.add(object);
            }
            result.count = keptObjects.size();
            result.values = keptObjects;
        } else {
            // add all objects
            result.values = sourceObjects;
            result.count = sourceObjects.size();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void publishResults(CharSequence constraint, FilterResults results) {
        filteredArrayAdapter.clear();
        if (results.count > 0) {
            Collection<T> objects = (Collection<T>) results.values;
            if (objects != null) {
                for (T object : objects) {
                    filteredArrayAdapter.add(object);
                }
            }
            filteredArrayAdapter.notifyDataSetChanged();
        } else {
            filteredArrayAdapter.notifyDataSetInvalidated();
        }
    }
}
