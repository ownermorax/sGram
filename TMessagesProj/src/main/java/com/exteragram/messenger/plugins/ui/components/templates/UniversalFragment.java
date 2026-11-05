package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * sGram: экран-шаблон с делегатом, как в exteraGram (нужен Hidden Folder и DEX-плагинам, которые строят свои экраны).
 */
public class UniversalFragment extends org.telegram.ui.Components.UniversalFragment {

    public interface UniversalFragmentDelegate {
        CharSequence getTitle();
        void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);
        void onClick(UItem item, View view, int position, float x, float y);
        boolean onLongClick(UItem item, View view, int position, float x, float y);
        void onFragmentCreate();
        void onFragmentDestroy();
        void beforeCreateView(Context context);
        View afterCreateView(FrameLayout contentView);
        void onMenuItemClick(int id);
        /** TRUE — нажатие обработано, экран не закрывать. */
        Object onBackPressed();
    }

    private final UniversalFragmentDelegate delegate;

    public UniversalFragment(UniversalFragmentDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean onFragmentCreate() {
        delegate.onFragmentCreate();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        delegate.onFragmentDestroy();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        delegate.beforeCreateView(context);
        View view = super.createView(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!Boolean.TRUE.equals(delegate.onBackPressed())) finishFragment();
                } else {
                    delegate.onMenuItemClick(id);
                }
            }
        });
        View custom = delegate.afterCreateView((FrameLayout) view);
        if (custom != null && custom != view) fragmentView = custom;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // sGram: Hidden Folder — вход по отпечатку поверх экрана код-пароля
        org.telegram.messenger.SgramHiddenFolder.onScreenResumed(this);
    }

    public ActionBarMenu getActionBarMenu() {
        return actionBar.createMenu();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (invoked && Boolean.TRUE.equals(delegate.onBackPressed())) return false;
        return super.onBackPressed(invoked);
    }

    @Override
    protected CharSequence getTitle() {
        return delegate.getTitle();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        delegate.fillItems(items, adapter);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        delegate.onClick(item, view, position, x, y);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return delegate.onLongClick(item, view, position, x, y);
    }
}
