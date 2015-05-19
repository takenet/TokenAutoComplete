package com.tokenautocomplete;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.QwertyKeyListener;
import android.text.style.ReplacementSpan;
import android.text.style.TextAppearanceSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.Filter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gmail style auto complete view with easy token customization
 * override getViewForObject to provide your token view
 * <p/>
 * Created by mgod on 9/12/13.
 *
 * @author mgod
 */
public abstract class TokenCompleteTextView extends MultiAutoCompleteTextView implements TextView.OnEditorActionListener {


    protected char[] splitChar = {','};
    boolean inInvalidate = false;
    private Tokenizer tokenizer;
    private Object selectedObject;
    private TokenListener listener;
    private TokenSpanWatcher spanWatcher;
    private ArrayList<Object> objects;
    private TokenDeleteStyle deletionStyle = TokenDeleteStyle._Parent;
    private TokenClickStyle tokenClickStyle = TokenClickStyle.None;
    private TokenImageSpan selectedToken;
    private String prefix = "";
    private boolean hintVisible = false;
    private Layout lastLayout = null;
    private boolean allowDuplicates = true;
    private boolean initialized = false;
    private boolean performBestGuess = true;
    private boolean savingState = false;
    private boolean shouldFocusNext = false;
    private boolean allowCollapse = true;

    public TokenCompleteTextView(Context context) {
        super(context);
        init();
    }

    public TokenCompleteTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TokenCompleteTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void resetListeners() {
        //reset listeners that get discarded when you set text
        Editable text = getText();
        if (text != null) {
            text.setSpan(spanWatcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            //This handles some cases where older Android SDK versions don't send onSpanRemoved
            //Needed in 2.2, 2.3.3, 3.0
            //Not needed after 4.0
            //I haven't tested on other 3.x series SDKs
            if (Build.VERSION.SDK_INT < 14) {
                addTextChangedListener(new TokenTextWatcherAPI8());
            } else {
                addTextChangedListener(new TokenTextWatcher());
            }
        }
    }

    private void init() {
        setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());
        objects = new ArrayList<>();
        Editable text = getText();
        assert null != text;
        spanWatcher = new TokenSpanWatcher();

        resetListeners();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            setTextIsSelectable(false);
        }

        setLongClickable(false);

        //In theory, get the soft keyboard to not supply suggestions.
        setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);

        setOnEditorActionListener(this);
        setFilters(new InputFilter[]{new InputFilter() {

            @Override
            public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
                //Detect split characters, remove them and complete the current token instead
                if (source.length() == 1) {
                    boolean isSplitChar = false;
                    for (char c : splitChar) isSplitChar = source.charAt(0) == c || isSplitChar;
                    if (isSplitChar) {
                        performCompletion();
                        return "";
                    }
                }

                //We need to not do anything when we would delete the prefix
                if (dstart < prefix.length() && dend == prefix.length()) {
                    return prefix.substring(dstart, dend);
                }
                return null;
            }
        }});

        //We had _Parent style during initialization to handle an edge case in the parent
        //now we can switch to Clear, usually the best choice
        setDeletionStyle(TokenDeleteStyle.Clear);
        initialized = true;
    }

    @Override
    protected void performFiltering(@NonNull CharSequence text, int start, int end,
                                    int keyCode) {
        if (start < prefix.length()) {
            start = prefix.length();
        }
        Filter filter = getFilter();
        if (filter != null) {
            filter.filter(text.subSequence(start, end), this);
        }
    }

    @Override
    public void setTokenizer(Tokenizer t) {
        super.setTokenizer(t);
        tokenizer = t;
    }

    public void setDeletionStyle(TokenDeleteStyle dStyle) {
        deletionStyle = dStyle;
    }

    @SuppressWarnings("unused")
    public void setTokenClickStyle(TokenClickStyle cStyle) {
        tokenClickStyle = cStyle;
    }

    public void setTokenListener(TokenListener l) {
        listener = l;
    }

    public void setPrefix(String p) {
        //Have to clear and set the actual text before saving the prefix to avoid the prefix filter
        prefix = "";
        Editable text = getText();
        if (text != null) {
            text.insert(0, p);
        }
        prefix = p;

        updateHint();
    }

    public List<Object> getObjects() {
        return objects;
    }

    public void setSplitChar(char[] splitChar) {
        this.splitChar = splitChar;
        // Keep the tokenizer and splitchars in sync
        this.setTokenizer(new CharacterTokenizer(splitChar));
    }

    public void setSplitChar(char splitChar) {
        this.setSplitChar(new char[]{splitChar});
    }

    /**
     * Sets whether to allow duplicate objects. If false, when the user selects
     * an object that's already in the view, the current text is just cleared.
     * <p/>
     * Defaults to true. Requires that the objects implement equals() correctly.
     */
    @SuppressWarnings("unused")
    public void allowDuplicates(boolean allow) {
        allowDuplicates = allow;
    }

    public void performBestGuess(boolean guess) {
        performBestGuess = guess;
    }

    /**
     * Sets whether to allow duplicate objects. If false, when the user selects
     * an object that's already in the view, the current text is just cleared.
     * <p/>
     * Defaults to true. Requires that the objects implement equals() correctly.
     */
    @SuppressWarnings("unused")
    public void allowCollapse(boolean allow) {
        allowCollapse = allow;
    }

    /**
     * A token view for the object
     *
     * @param object the object selected by the user from the list
     * @return a view to display a token in the text field for the object
     */
    abstract protected View getViewForObject(Object object);

    /**
     * Provides a default completion when the user hits , and there is no item in the completion
     * list
     *
     * @param completionText the current text we are completing against
     * @return a best guess for what the user meant to complete
     */
    abstract protected Object defaultObject(String completionText);

    protected String currentCompletionText() {
        if (hintVisible) return ""; //Can't have any text if the hint is visible

        Editable editable = getText();
        int end = getSelectionEnd();
        int start = tokenizer.findTokenStart(editable, end);
        if (start < prefix.length()) {
            start = prefix.length();
        }
        return TextUtils.substring(editable, start, end);
    }

    private float maxTextWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void api16Invalidate() {
        if (initialized && !inInvalidate) {
            inInvalidate = true;
            setShadowLayer(getShadowRadius(), getShadowDx(), getShadowDy(), getShadowColor());
            inInvalidate = false;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void invalidate() {
        //Need to force the TextView private mEditor variable to reset as well on API 16 and up
        if (Build.VERSION.SDK_INT >= 16 && initialized && !inInvalidate) {
            inInvalidate = true;
            setShadowLayer(getShadowRadius(), getShadowDx(), getShadowDy(), getShadowColor());
            inInvalidate = false;
        }
        super.invalidate();
    }

    @Override
    public boolean enoughToFilter() {
        Editable text = getText();

        int end = getSelectionEnd();
        if (end < 0 || tokenizer == null) {
            return false;
        }

        int start = tokenizer.findTokenStart(text, end);
        if (start < prefix.length()) {
            start = prefix.length();
        }

        return end - start >= getThreshold();
    }

    @Override
    public void performCompletion() {
        if (getListSelection() == ListView.INVALID_POSITION) {
            Object bestGuess = defaultObject(currentCompletionText());
            if (bestGuess != null) {
                replaceText(convertSelectionToString(bestGuess));
                return;
            }
        }
        super.performCompletion();
    }

    private Object getBestGuess() {
        String currentText = currentCompletionText();
        if (TextUtils.isEmpty(currentText)) {
            return null; // indicates the user just typed a single ',', so no text
        }
        ListAdapter adapter = getAdapter();
        if (adapter.getCount() > 0) {
            Object firstItem = adapter.getItem(0);
            if (currentText.equals(convertSelectionToString(firstItem))) {
                // prefixes aren't good enough; we want it to match the full string
                return firstItem;
            }

        }
        // create a new tag
        return defaultObject(currentText);
    }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {
        //Override normal multiline text handling of enter/done and force a done button
        TokenInputConnection tokenInputConnection = new TokenInputConnection(super.onCreateInputConnection(outAttrs), true);
        int imeActions = outAttrs.imeOptions & EditorInfo.IME_MASK_ACTION;
        if ((imeActions & EditorInfo.IME_ACTION_DONE) != 0) {
            // clear the existing action
            outAttrs.imeOptions ^= imeActions;
            // set the DONE action
            outAttrs.imeOptions |= EditorInfo.IME_ACTION_DONE;
        }
        if ((outAttrs.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            outAttrs.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }
        return tokenInputConnection;
    }

    private void handleDone() {
        //If there is enough text to filter, attempt to complete the token
        if (enoughToFilter()) {
            performCompletion();
        } else {
            //...otherwise look for the next field and focus it
            //TODO: should clear existing text as well

            View next = focusSearch(View.FOCUS_DOWN);
            if (next != null) {
                next.requestFocus();
            }
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        boolean handled = super.onKeyUp(keyCode, event);
        if (shouldFocusNext) {
            shouldFocusNext = false;
            handleDone();
        }
        return handled;
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        boolean handled = false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (Build.VERSION.SDK_INT >= 11) {
                    if (event.hasNoModifiers()) {
                        shouldFocusNext = true;
                        handled = true;
                    }
                } else {
                    shouldFocusNext = true;
                    handled = true;
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                handled = deleteSelectedObject(false);
                break;
        }

        return handled || super.onKeyDown(keyCode, event);
    }

    private boolean deleteSelectedObject(boolean handled) {
        if (tokenClickStyle != null && tokenClickStyle.isSelectable()) {
            Editable text = getText();
            if (text == null) return handled;

            TokenImageSpan[] spans = text.getSpans(0, text.length(), TokenImageSpan.class);
            for (TokenImageSpan span : spans) {
                if (span.view.isSelected()) {
                    removeSpan(span);
                    handled = true;
                    break;
                }
            }
        }
        return handled;
    }

    @Override
    public boolean onEditorAction(TextView view, int action, KeyEvent keyEvent) {
        if (action == EditorInfo.IME_ACTION_DONE) {
            handleDone();
            return true;
        }
        return false;
    }

    public TokenImageSpan getTokenOnPosition(float x, float y)
    {
        Editable text = getText();

        int offset;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            offset = TextPositionCompatibilityAPI8.getOffsetForPosition(x, y, this, lastLayout);
        } else {
            offset = getOffsetForPosition(x, y);
        }

        if (offset != -1) {
            TokenImageSpan[] links = text.getSpans(offset, offset, TokenImageSpan.class);

            if (links.length > 0) {
                return links[0];
            }
        }

        return null;
    }

    @Override
    @TargetApi(14)
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        Editable text = getText();
        boolean handled = false;

        if (objects.size() > 0 && action == MotionEvent.ACTION_DOWN) {
            setOnLongClickListener(null);
            selectedToken = getTokenOnPosition(event.getX(), event.getY());
            if( selectedToken != null ) {
                selectedToken.setOnTokenLongClickListener(getOnTokenLongClickListener());
                selectedToken.setOnTokenClickListener( getOnTokenClickListener() );

                OnLongClickListener longClickListener = new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {

                        if (selectedToken != null) {
                            selectedToken.onLongClick();
                            selectedToken = null;
                            setOnLongClickListener(null);
                        }

                        return true;
                    }
                };

                setOnLongClickListener(longClickListener);
            }
        }

        if( action == MotionEvent.ACTION_DOWN){
            if( !isFocused() && allowCollapse ){
                setSingleLine(false);
                focusOnField();
            }
        }

        if (tokenClickStyle == TokenClickStyle.None) {
            handled = super.onTouchEvent(event);
        }

        if (isFocused() && text != null && lastLayout != null && action == MotionEvent.ACTION_UP) {

            TokenImageSpan linkToken = getTokenOnPosition(event.getX(), event.getY());

            if( linkToken != null ) {
                linkToken.onClick();
                handled = true;
            }
        }

        if (!handled && tokenClickStyle != TokenClickStyle.None) {
            handled = super.onTouchEvent(event);
        }

        return handled;

    }

    abstract protected OnTokenClickListener getOnTokenClickListener();
    abstract protected OnTokenLongClickListener getOnTokenLongClickListener();

    @Override
    public void setOnLongClickListener(OnLongClickListener l) {
        super.setOnLongClickListener(l);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        if (hintVisible) {
            //Don't let users select the hint
            selStart = 0;
        }
        //Never let users select text
        selEnd = selStart;

        if (tokenClickStyle != null && tokenClickStyle.isSelectable()) {
            Editable text = getText();
            if (text != null) {
                clearSelections();
            }
        }


        if (prefix != null && (selStart < prefix.length() || selEnd < prefix.length())) {
            //Don't let users select the prefix
            setSelection(prefix.length());
        } else {
            Editable text = getText();
            if (text != null) {
                //Make sure if we are in a span, we select the spot 1 space after the span end
                TokenImageSpan[] spans = text.getSpans(selStart, selEnd, TokenImageSpan.class);
                for (TokenImageSpan span : spans) {
                    int spanEnd = text.getSpanEnd(span);
                    if (selStart <= spanEnd && text.getSpanStart(span) < selStart) {
                        if (spanEnd == text.length())
                            setSelection(spanEnd);
                        else
                            setSelection(spanEnd + 1);
                        return;
                    }
                }

            }

            super.onSelectionChanged(selStart, selEnd);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        lastLayout = getLayout(); //Used for checking text positions
    }

    protected void handleFocus(boolean hasFocus) {
        if (!hasFocus && allowCollapse) {
            // See if the user left any unfinished tokens and finish them
            if (enoughToFilter()) performCompletion();

            setSingleLine(true);

            Editable text = getText();
            if (text != null && lastLayout != null) {
                //Display +x thingy if appropriate
                int lastPosition = lastLayout.getLineVisibleEnd(0);
                TokenImageSpan[] tokens = text.getSpans(0, lastPosition, TokenImageSpan.class);
                int count = objects.size() - tokens.length;
                if (count > 0) {
                    lastPosition++;
                    CountSpan cs = new CountSpan(count, getContext(), getCurrentTextColor(),
                            (int) getTextSize(), (int) maxTextWidth());
                    text.insert(lastPosition, cs.text);

                    float newWidth = Layout.getDesiredWidth(text, 0,
                            lastPosition + cs.text.length(), lastLayout.getPaint());
                    //If the +x span will be moved off screen, move it one token in
                    if (newWidth > maxTextWidth()) {
                        text.delete(lastPosition, lastPosition + cs.text.length());

                        if (tokens.length > 0) {
                            TokenImageSpan token = tokens[tokens.length - 1];
                            lastPosition = text.getSpanStart(token);
                            cs.setCount(count + 1);
                        } else {
                            lastPosition = prefix.length();
                        }

                        text.insert(lastPosition, cs.text);
                    }

                    text.setSpan(cs, lastPosition, lastPosition + cs.text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }


        }
        else
        {
            focusOnField();
        }
    }

    private void focusOnField()
    {
        Editable text = getText();
        if (text != null) {
            CountSpan[] counts = text.getSpans(0, text.length(), CountSpan.class);
            for (CountSpan count : counts) {
                text.delete(text.getSpanStart(count), text.getSpanEnd(count));
                text.removeSpan(count);
            }

            if (hintVisible) {
                setSelection(prefix.length());
            } else {
                setSelection(text.length());
            }

            TokenSpanWatcher[] watchers = getText().getSpans(0, getText().length(), TokenSpanWatcher.class);
            if (watchers.length == 0) {
                //Someone removes watchers? I'm pretty sure this isn't in this code... -mgod
                text.setSpan(spanWatcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
        }
    }

    @Override
    public void onFocusChanged(boolean hasFocus, int direction, Rect previous) {
        super.onFocusChanged(hasFocus, direction, previous);

        try {
            handleFocus(hasFocus);
        }
        catch (Exception ex)
        {}

    }

    @Override
    protected CharSequence convertSelectionToString(Object object) {
        selectedObject = object;

        //if the token gets deleted, this text will get put in the field instead
        switch (deletionStyle) {
            case Clear:
                return "";
            case PartialCompletion:
                return currentCompletionText();
            case ToString:
                return object.toString();
            case _Parent:
            default:
                return super.convertSelectionToString(object);

        }
    }

    private SpannableStringBuilder buildSpannableForText(CharSequence text) {
        return new SpannableStringBuilder(" " + tokenizer.terminateToken(text));
    }

    protected TokenImageSpan buildSpanForObject(Object obj) {
        if (obj == null) {
            return null;
        }
        View tokenView = getViewForObject(obj);
        return new TokenImageSpan(tokenView, obj);
    }

    @Override
    protected void replaceText(CharSequence text) {
        clearComposingText();

        // Don't build a token for an empty String
        if (selectedObject.toString().equals("")) return;

        SpannableStringBuilder ssb = buildSpannableForText(text);
        TokenImageSpan tokenSpan = buildSpanForObject(selectedObject);

        Editable editable = getText();
        int end = getSelectionEnd();
        int start = tokenizer.findTokenStart(editable, end);
        if (start < prefix.length()) {
            start = prefix.length();
        }
        while (start - 2 > 0 && editable.charAt(start - 2) == ' ') {
            start--;
        }
        String original = TextUtils.substring(editable, start, end);

        if (editable != null) {
            if (tokenSpan == null) {
                editable.replace(start, end, " ");
            } else if (!allowDuplicates && objects.contains(tokenSpan.getToken())) {
                editable.replace(start, end, " ");
            } else {
                QwertyKeyListener.markAsReplaced(editable, start, end, original);
                editable.replace(start, end, ssb);
                editable.setSpan(tokenSpan, start, start + ssb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /**
     * Append a token object to the object list
     *
     * @param object     the object to add to the displayed tokens
     * @param sourceText the text used if this object is deleted
     */
    public void addObject(final Object object, final CharSequence sourceText) {
        post(new Runnable() {
            @Override
            public void run() {
                if (object == null) return;
                if (!allowDuplicates && objects.contains(object)) return;

                SpannableStringBuilder ssb = buildSpannableForText(sourceText);
                TokenImageSpan tokenSpan = buildSpanForObject(object);

                Editable editable = getText();
                if (editable != null) {
                    int offset = editable.length();
                    //There might be a hint visible...
                    if (hintVisible) {
                        //...so we need to put the object in in front of the hint
                        offset = prefix.length();
                        editable.insert(offset, ssb);
                    } else {
                        editable.append(ssb);
                    }
                    editable.setSpan(tokenSpan, offset, offset + ssb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                    //In some cases, particularly the 1 to nth objects when not focused and restoring
                    //onSpanAdded doesn't get called
                    if (!objects.contains(object)) {
                        spanWatcher.onSpanAdded(editable, tokenSpan, offset, offset + ssb.length() - 1);
                    }

                    setSelection(editable.length());
                }
            }
        });
    }

    /**
     * Shorthand for addObject(object, "")
     *
     * @param object the object to add to the displayed token
     */
    public void addObject(Object object) {
        addObject(object, "");
    }

    /**
     * Remove an object from the token list. Will remove duplicates or do nothing if no object
     * present in the view.
     *
     * @param object object to remove, may be null or not in the view
     */
    @SuppressWarnings("unused")
    public void removeObject(final Object object) {
        post(new Runnable() {
            @Override
            public void run() {
                //To make sure all the appropriate callbacks happen, we just want to piggyback on the
                //existing code that handles deleting spans when the text changes
                Editable text = getText();
                if (text == null) return;

                TokenImageSpan[] spans = text.getSpans(0, text.length(), TokenImageSpan.class);
                for (TokenImageSpan span : spans) {
                    if (span.getToken().equals(object)) {
                        removeSpan(span);
                    }
                }
            }
        });
    }

    private void removeSpan(TokenImageSpan span) {
        Editable text = getText();
        if (text == null) return;

        //If the spanwatcher has been removed, we need to also manually trigger onSpanRemoved
        TokenSpanWatcher[] spans = text.getSpans(0, text.length(), TokenSpanWatcher.class);
        if (spans.length == 0) {
            spanWatcher.onSpanRemoved(text, span, text.getSpanStart(span), text.getSpanEnd(span));
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            //HACK: Need to manually trigger on Span removed if there is only 1 object
            //not sure if there's a cleaner way
            if (objects.size() == 1) {
                spanWatcher.onSpanRemoved(text, span, text.getSpanStart(span), text.getSpanEnd(span));
            }
        }

        //Add 1 to the end because we put a " " at the end of the spans when adding them
        text.delete(text.getSpanStart(span), text.getSpanEnd(span) + 1);
    }

    private void updateHint() {
        Editable text = getText();
        CharSequence hintText = getHint();
        if (text == null || hintText == null) {
            return;
        }

        //Show hint if we need to
        if (prefix.length() > 0) {
            TextAppearanceSpan[] hints = text.getSpans(0, text.length(), TextAppearanceSpan.class);
            TextAppearanceSpan hint = null;
            int testLength = prefix.length();
            if (hints.length > 0) {
                hint = hints[0];
                testLength += text.getSpanEnd(hint) - text.getSpanStart(hint);
            }

            if (text.length() == testLength) {
                hintVisible = true;

                if (hint != null) {
                    return;//hint already visible
                }

                //We need to display the hint manually
                Typeface tf = getTypeface();
                int style = Typeface.NORMAL;
                if (tf != null) {
                    style = tf.getStyle();
                }
                ColorStateList colors = getHintTextColors();

                TextAppearanceSpan hintSpan = new TextAppearanceSpan(null, style, (int) getTextSize(), colors, colors);
                text.insert(prefix.length(), hintText);
                text.setSpan(hintSpan, prefix.length(), prefix.length() + getHint().length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                setSelection(prefix.length());

            } else {
                if (hint == null) {
                    return; //hint already removed
                }

                //Remove the hint. There should only ever be one
                int sStart = text.getSpanStart(hint);
                int sEnd = text.getSpanEnd(hint);

                text.removeSpan(hint);
                text.replace(sStart, sEnd, "");

                hintVisible = false;
            }
        }
    }

    private void clearSelections() {
        if (tokenClickStyle == null || !tokenClickStyle.isSelectable()) return;

        Editable text = getText();
        if (text == null) return;

        TokenImageSpan[] tokens = text.getSpans(0, text.length(), TokenImageSpan.class);
        for (TokenImageSpan token : tokens) {
            token.view.setSelected(false);
        }
        invalidate();
    }

    protected ArrayList<Serializable> getSerializableObjects() {
        ArrayList<Serializable> serializables = new ArrayList<>();
        for (Object obj : getObjects()) {
            if (obj instanceof Serializable) {
                serializables.add((Serializable) obj);
            } else {
                System.out.println("Unable to save '" + obj + "'");
            }
        }
        if (serializables.size() != objects.size()) {
            System.out.println("You should make your objects Serializable or override");
            System.out.println("getSerializableObjects and convertSerializableArrayToObjectArray");
        }

        return serializables;
    }

    @SuppressWarnings("unchecked")
    protected ArrayList<Object> convertSerializableArrayToObjectArray(ArrayList<Serializable> s) {
        return (ArrayList<Object>) (ArrayList) s;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        ArrayList<Serializable> baseObjects = getSerializableObjects();

        //ARGH! Apparently, saving the parent state on 2.3 mutates the spannable
        //prevent this mutation from triggering add or removes of token objects ~mgod
        savingState = true;
        Parcelable superState = super.onSaveInstanceState();
        savingState = false;
        SavedState state = new SavedState(superState);

        state.prefix = prefix;
        state.allowDuplicates = allowDuplicates;
        state.performBestGuess = performBestGuess;
        state.tokenClickStyle = tokenClickStyle;
        state.tokenDeleteStyle = deletionStyle;
        state.baseObjects = baseObjects;
        state.splitChar = splitChar;

        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        setText(ss.prefix);
        prefix = ss.prefix;
        updateHint();
        allowDuplicates = ss.allowDuplicates;
        performBestGuess = ss.performBestGuess;
        tokenClickStyle = ss.tokenClickStyle;
        deletionStyle = ss.tokenDeleteStyle;
        splitChar = ss.splitChar;

        resetListeners();
        for (Object obj : convertSerializableArrayToObjectArray(ss.baseObjects)) {
            addObject(obj);
        }

        //This needs to happen after all the objects get added (which also get posted)
        //or the view truncates really oddly
        if (!isFocused()) {
            post(new Runnable() {
                @Override
                public void run() {
                    //Resize the view nad display the +x if appropriate
                    handleFocus(isFocused());
                }
            });
        }


    }

    //When the token is deleted...
    public enum TokenDeleteStyle {
        _Parent, //...do the parent behavior, not recommended
        Clear, //...clear the underlying text
        PartialCompletion, //...return the original text used for completion
        ToString //...replace the token with toString of the token object
    }

    //When the user clicks on a token...
    public enum TokenClickStyle {
        None(false), //...do nothing, but make sure the cursor is not in the token
        Delete(false),//...delete the token
        Select(true),//...select the token. A second click will delete it.
        SelectDeselect(true);

        private boolean mIsSelectable = false;

        TokenClickStyle(final boolean selectable) {
            mIsSelectable = selectable;
        }

        public boolean isSelectable() {
            return mIsSelectable;
        }
    }

    public interface TokenListener {
        void onTokenAdded(Object token);

        void onTokenRemoved(Object token);
    }

    /**
     * Handle saving the token state
     */
    private static class SavedState extends BaseSavedState {
        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        String prefix;
        boolean allowDuplicates;
        boolean performBestGuess;
        TokenClickStyle tokenClickStyle;
        TokenDeleteStyle tokenDeleteStyle;
        ArrayList<Serializable> baseObjects;
        char[] splitChar;

        @SuppressWarnings("unchecked")
        SavedState(Parcel in) {
            super(in);
            prefix = in.readString();
            allowDuplicates = in.readInt() != 0;
            performBestGuess = in.readInt() != 0;
            tokenClickStyle = TokenClickStyle.values()[in.readInt()];
            tokenDeleteStyle = TokenDeleteStyle.values()[in.readInt()];
            baseObjects = (ArrayList<Serializable>) in.readSerializable();
            splitChar = in.createCharArray();
        }

        SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeString(prefix);
            out.writeInt(allowDuplicates ? 1 : 0);
            out.writeInt(performBestGuess ? 1 : 0);
            out.writeInt(tokenClickStyle.ordinal());
            out.writeInt(tokenDeleteStyle.ordinal());
            out.writeSerializable(baseObjects);
            out.writeCharArray(splitChar);
        }

        @Override
        public String toString() {
            String str = "TokenCompleteTextView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " tokens=" + baseObjects;
            return str + "}";
        }
    }

    private static class TextPositionCompatibilityAPI8 {
        //Borrowing some code from API 14
        static public int getOffsetForPosition(float x, float y, TextView tv, Layout layout) {
            if (layout == null) return -1;
            final int line = getLineAtCoordinate(y, tv, layout);
            return getOffsetAtCoordinate(line, x, tv, layout);
        }

        static private float convertToLocalHorizontalCoordinate(float x, TextView tv) {
            if (tv.getLayout() == null) {
                x -= tv.getCompoundPaddingLeft();
            } else {
                x -= tv.getTotalPaddingLeft();
            }
            // Clamp the position to inside of the view.
            x = Math.max(0.0f, x);
            float rightSide = tv.getWidth() - 1;
            if (tv.getLayout() == null) {
                rightSide -= tv.getCompoundPaddingRight();
            } else {
                rightSide -= tv.getTotalPaddingRight();
            }
            x = Math.min(rightSide, x);
            x += tv.getScrollX();
            return x;
        }

        static private int getLineAtCoordinate(float y, TextView tv, Layout layout) {
            if (tv.getLayout() == null) {
                y -= tv.getCompoundPaddingTop();
            } else {
                y -= tv.getTotalPaddingTop();
            }
            // Clamp the position to inside of the view.
            y = Math.max(0.0f, y);
            float bottom = tv.getHeight() - 1;
            if (tv.getLayout() == null) {
                bottom -= tv.getCompoundPaddingBottom();
            } else {
                bottom -= tv.getTotalPaddingBottom();
            }
            y = Math.min(bottom, y);
            y += tv.getScrollY();
            return layout.getLineForVertical((int) y);
        }

        static private int getOffsetAtCoordinate(int line, float x, TextView tv, Layout layout) {
            x = convertToLocalHorizontalCoordinate(x, tv);
            return layout.getOffsetForHorizontal(line, x);
        }
    }

    private class ViewSpan extends ReplacementSpan {
        protected View view;

        public ViewSpan(View v) {
            view = v;
        }

        private void prepView() {
            int widthSpec = MeasureSpec.makeMeasureSpec((int) maxTextWidth(), MeasureSpec.AT_MOST);
            int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        }

        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            prepView();

            canvas.save();
            //Centering the token looks like a better strategy that aligning the bottom
            int padding = (bottom - top - view.getBottom()) / 2;
            canvas.translate(x, bottom - view.getBottom() - padding);
            view.draw(canvas);
            canvas.restore();
        }

        public int getSize(Paint paint, CharSequence charSequence, int i, int i2, Paint.FontMetricsInt fm) {
            prepView();

            if (fm != null) {
                //We need to make sure the layout allots enough space for the view
                int height = view.getMeasuredHeight();
                int need = height - (fm.descent - fm.ascent);
                if (need > 0) {
                    int ascent = need / 2;
                    //This makes sure the text drawing area will be tall enough for the view
                    fm.descent += need - ascent;
                    fm.ascent -= ascent;
                    fm.bottom += need - ascent;
                    fm.top -= need / 2;
                }
            }

            return view.getRight();
        }
    }

    private class CountSpan extends ViewSpan {
        public String text = "";
        private int count;

        public CountSpan(int count, Context ctx, int textColor, int textSize, int maxWidth) {
            super(new TextView(ctx));
            TextView v = (TextView) view;
            v.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            v.setTextColor(textColor);
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            //Make the view as wide as the parent to push the tokens off screen
            v.setMinimumWidth(maxWidth);
            setCount(count);
        }

        public int getCount() {
            return count;
        }

        public void setCount(int c) {
            count = c;
            text = "+" + count;
            ((TextView) view).setText(text);
        }
    }

    protected class TokenImageSpan extends ViewSpan {
        private Object token;
        private OnTokenClickListener onTokenClickListener;
        private OnTokenLongClickListener onTokenLongClickListener;

        public TokenImageSpan(View d, Object token) {
            super(d);
            this.token = token;
        }

        public Object getToken() {
            return this.token;
        }

        public void onClick() {
            Editable text = getText();
            if (text == null) return;

            switch (tokenClickStyle) {
                case Select:
                case SelectDeselect:

                    if (!view.isSelected()) {
                        clearSelections();
                        view.setSelected(true);
                        break;
                    }

                    if (tokenClickStyle == TokenClickStyle.SelectDeselect) {
                        view.setSelected(false);
                        invalidate();
                        break;
                    }

                    //If the view is already selected, we want to delete it
                case Delete:
                    removeSpan(this);
                    break;
                case None:
                default:
                    if (getSelectionStart() != text.getSpanEnd(this) + 1) {
                        //Make sure the selection is not in the middle of the span
                        setSelection(text.getSpanEnd(this) + 1);
                    }
            }

            if( onTokenClickListener != null )
            {
                onTokenClickListener.onClick(this);
            }
        }

        public void onLongClick()
        {
            if( onTokenLongClickListener != null )
            {
                onTokenLongClickListener.onLongClick(this);
            }
        }

        public void setOnTokenClickListener(OnTokenClickListener clickListener)
        {
            this.onTokenClickListener = clickListener;
        }

        public void setOnTokenLongClickListener(OnTokenLongClickListener longClickListener)
        {
            this.onTokenLongClickListener = longClickListener;
        }



    }
    public static interface OnTokenClickListener
    {
        void onClick(TokenImageSpan tokenImageSpan);
    }

    public static interface OnTokenLongClickListener
    {
        void onLongClick(TokenImageSpan tokenImageSpan);
    }

    private class TokenSpanWatcher implements SpanWatcher {
        private void updateCountSpan(final int change) {
            final Editable text = getText();
            if (text == null || lastLayout == null) return;

            CountSpan[] counts = text.getSpans(0, text.length(), CountSpan.class);
            if (counts.length == 1) {
                final CountSpan span = counts[0];
                post(new Runnable() {
                    @Override
                    public void run() {
                        int spanStart = text.getSpanStart(span);
                        int spanEnd = text.getSpanEnd(span);
                        span.setCount(span.getCount() + change);
                        if (span.getCount() > 0) {
                            text.replace(spanStart, spanEnd, span.text);
                        } else {
                            text.delete(spanStart, spanEnd);
                            text.removeSpan(span);
                        }
                    }
                });

            }
        }

        @Override
        public void onSpanAdded(Spannable text, Object what, int start, int end) {
            if (what instanceof TokenImageSpan && !savingState) {
                TokenImageSpan token = (TokenImageSpan) what;
                objects.add(token.getToken());
                updateCountSpan(1);

                if (listener != null)
                    listener.onTokenAdded(token.getToken());
            }
        }

        @Override
        public void onSpanRemoved(Spannable text, Object what, int start, int end) {
            if (what instanceof TokenImageSpan && !savingState) {
                TokenImageSpan token = (TokenImageSpan) what;
                if (objects.contains(token.getToken())) {
                    objects.remove(token.getToken());
                    updateCountSpan(-1);
                }

                if (listener != null)
                    listener.onTokenRemoved(token.getToken());
            }
        }

        @Override
        public void onSpanChanged(Spannable text, Object what, int ostart, int oend, int nstart, int nend) {
        }
    }

    /**
     * deletes tokens if you delete the space in front of them
     * without this, you get the auto-complete dropdown a character early
     */
    private class TokenTextWatcher implements TextWatcher {

        protected void removeToken(TokenImageSpan token, Editable text) {
            text.removeSpan(token);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void afterTextChanged(Editable s) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            System.out.println("changing text: " + s);
            Editable text = getText();
            if (text == null)
                return;

            clearSelections();
            updateHint();

            TokenImageSpan[] spans = text.getSpans(start - before, start - before + count, TokenImageSpan.class);

            for (TokenImageSpan token : spans) {

                int position = start + count;
                if (text.getSpanStart(token) < position && position <= text.getSpanEnd(token)) {
                    //We may have to manually reverse the auto-complete and remove the extra ,'s
                    int spanStart = text.getSpanStart(token);
                    int spanEnd = text.getSpanEnd(token);

                    removeToken(token, text);

                    //The end of the span is the character index after it
                    spanEnd--;

                    if (spanEnd >= 0 && text.charAt(spanEnd) == ',') {
                        text.delete(spanEnd, spanEnd + 1);
                    }

                    if (spanStart > 0 && text.charAt(spanStart) == ',') {
                        text.delete(spanStart, spanStart + 1);
                    }
                }
            }
        }
    }

    private class CharacterTokenizer implements Tokenizer {
        ArrayList<Character> splitChar;

        CharacterTokenizer(char[] splitChar) {
            super();
            this.splitChar = new ArrayList<>(splitChar.length);
            for (char c : splitChar) this.splitChar.add(c);
        }

        public int findTokenStart(CharSequence text, int cursor) {
            int i = cursor;

            while (i > 0 && !splitChar.contains(text.charAt(i - 1))) {
                i--;
            }
            while (i < cursor && text.charAt(i) == ' ') {
                i++;
            }

            return i;
        }

        public int findTokenEnd(CharSequence text, int cursor) {
            int i = cursor;
            int len = text.length();

            while (i < len) {
                if (splitChar.contains(text.charAt(i))) {
                    return i;
                } else {
                    i++;
                }
            }

            return len;
        }

        public CharSequence terminateToken(CharSequence text) {
            int i = text.length();

            while (i > 0 && text.charAt(i - 1) == ' ') {
                i--;
            }

            if (i > 0 && splitChar.contains(text.charAt(i - 1))) {
                return text;
            } else {
                // Try not to use a space as a token character
                String token = (splitChar.size() > 1 && splitChar.get(0) == ' ' ? splitChar.get(1) : splitChar.get(0)) + " ";
                if (text instanceof Spanned) {
                    SpannableString sp = new SpannableString(text + token);
                    TextUtils.copySpansFrom((Spanned) text, 0, text.length(),
                            Object.class, sp, 0);
                    return sp;
                } else {
                    return text + token;
                }
            }
        }
    }

    private class TokenInputConnection extends InputConnectionWrapper {

        public TokenInputConnection(InputConnection target, boolean mutable) {
            super(target, mutable);
        }

        // This will fire if the soft keyboard delete key is pressed.
        // The onKeyPressed method does not always do this.
        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            System.out.println("before: " + beforeLength + " after: " + afterLength);
            System.out.println("selection: " + getSelectionStart() + " end: " + getSelectionEnd());

            //Shouldn't be able to delete prefix, so don't do anything
            if (getSelectionStart() <= prefix.length())
                beforeLength = 0;

            return deleteSelectedObject(false) || super.deleteSurroundingText(beforeLength, afterLength);
        }
    }

    /**
     * On some older versions of android sdk, the onSpanRemoved and onSpanChanged are not reliable
     * this class supplements the TokenSpanWatcher to manually trigger span updates
     */
    private class TokenTextWatcherAPI8 extends TokenTextWatcher {
        private ArrayList<TokenImageSpan> currentTokens = new ArrayList<>();

        @Override
        protected void removeToken(TokenImageSpan token, Editable text) {
            currentTokens.remove(token);
            super.removeToken(token, text);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            currentTokens.clear();
            Editable text = getText();
            if (text == null)
                return;

            TokenImageSpan[] spans = text.getSpans(0, text.length(), TokenImageSpan.class);
            currentTokens.addAll(Arrays.asList(spans));
        }

        @Override
        public void afterTextChanged(Editable s) {
            TokenImageSpan[] spans = s.getSpans(0, s.length(), TokenImageSpan.class);
            for (TokenImageSpan token : currentTokens) {
                if (!Arrays.asList(spans).contains(token)) {
                    spanWatcher.onSpanRemoved(s, token, s.getSpanStart(token), s.getSpanEnd(token));
                }
            }
        }
    }
}
