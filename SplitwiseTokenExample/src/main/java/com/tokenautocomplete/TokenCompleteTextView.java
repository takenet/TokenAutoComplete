package com.tokenautocomplete;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.QwertyKeyListener;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.MultiAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gmail style auto complete view with easy token customization
 * override getViewForObject to provide your token view
 *
 * Created by mgod on 9/12/13.
 *
 * @author mgod
 */
public abstract class TokenCompleteTextView extends MultiAutoCompleteTextView {
    //When the token is deleted...
    public enum TokenDeleteStyle {
        _Parent, //...do the parent behavior, not recommended
        Clear, //...clear the underlying text
        PartialCompletion, //...return the original text used for completion
        ToString //...replace the token with toString of the token object
    }

    private Tokenizer tokenizer;
    private Object selectedObject;
    private TokenListener listener;
    private TokenSpanWatcher spanWatcher;
    private ArrayList<Object> objects;
    private TokenDeleteStyle deletionStyle = TokenDeleteStyle._Parent;

    private void init() {
        setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());
        objects = new ArrayList<Object>();
        Editable text = getText();
        assert null != text;
        spanWatcher = new TokenSpanWatcher();
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

        //We had _Parent style during initialization to handle an edge case in the parent
        //now we can switch to Clear, usually the best choice
        setDeletionStyle(TokenDeleteStyle.Clear);
    }

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

    @Override
    public void setTokenizer(Tokenizer t) {
        super.setTokenizer(t);
        tokenizer = t;
    }

    public void setDeletionStyle(TokenDeleteStyle dStyle) {
        deletionStyle = dStyle;
    }

    public void setTokenListener(TokenListener l) {
        listener = l;
    }

    public List<Object> getObjects() {
        return objects;
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
        Editable editable = getText();
        int end = getSelectionEnd();
        int start = tokenizer.findTokenStart(editable, end);
        return TextUtils.substring(editable, start, end);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_COMMA) {
            if (getListSelection() == ListView.INVALID_POSITION) {
                Object bestGuess = null;
                if (getAdapter().getCount() > 0) {
                    bestGuess = getAdapter().getItem(0);
                } else {
                    bestGuess = defaultObject(currentCompletionText());
                }
                replaceText(convertSelectionToString(bestGuess));
            } else {
                performCompletion();
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
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

    @Override
    protected void replaceText(CharSequence text) {
        clearComposingText();

        //Add a sentinel , at the beginning so the user can remove an inner token and keep auto-completing
        //This is a hack to work around the fact that the tokenizer cannot directly detect spans
        SpannableStringBuilder ssb = new SpannableStringBuilder("," + tokenizer.terminateToken(text));

        View tokenView = getViewForObject(selectedObject);
        Drawable d = convertViewToDrawable(tokenView);

        TokenImageSpan tokenSpan = new TokenImageSpan(d, ssb.toString().substring(0, ssb.length() - 1), selectedObject);

        Editable editable = getText();
        int end = getSelectionEnd();
        int start = tokenizer.findTokenStart(editable, end);
        String original = TextUtils.substring(editable, start, end);

        if (editable != null) {
            QwertyKeyListener.markAsReplaced(editable, start, end, original);
            editable.replace(start, end, ssb);
            editable.setSpan(tokenSpan, start, start + ssb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }



    protected Drawable convertViewToDrawable(View view) {
        //TODO: I'm not really sure how to test that this gets a correctly sized image
        //Can anyone with more experience testing the drawing pipeline take a look?
        int widthSpec = MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

        Bitmap b = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        view.draw(c);
        Context ctx = getContext();
        assert null != ctx;
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), b);
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        return d;
    }

    public static class TokenImageSpan extends ImageSpan {
        private Object token;

        public TokenImageSpan(Drawable d, String source, Object token) {
            super(d, source);
            this.token = token;
        }

        public Object getToken() {
            return this.token;
        }
    }

    public static interface TokenListener {
        public void onTokenAdded(Object token);
        public void onTokenRemoved(Object token);
    }

    private class TokenSpanWatcher implements SpanWatcher {
        @Override
        public void onSpanAdded(Spannable text, Object what, int start, int end) {
            if (what instanceof TokenImageSpan) {
                TokenImageSpan token = (TokenImageSpan)what;
                objects.add(token.getToken());
                if (listener != null)
                    listener.onTokenAdded(token.getToken());
            }
        }

        @Override
        public void onSpanRemoved(Spannable text, Object what, int start, int end) {
            if (what instanceof TokenImageSpan) {
                TokenImageSpan token = (TokenImageSpan)what;
                objects.remove(token.getToken());
                if (listener != null)
                    listener.onTokenRemoved(token.getToken());
            }
        }

        @Override
        public void onSpanChanged(Spannable text, Object what, int ostart, int oend, int nstart, int nend) {
            if (what != null && what instanceof TokenImageSpan) {
                //Make sure we're not at the beginning of the span, as we only want to remove the span if we're deleting into it
                //If the end is not changing, this means we're deleting into the right side of the span
                if (oend == nend) {
                    int spanStart = text.getSpanStart(what);
                    //remove the span first to prevent recursion
                    text.removeSpan(what);
                    //Remove the first character in the span (our sentinel , from replaceText)
                    Editable content = getText();
                    if (content != null) {
                        content.delete(spanStart, spanStart + 1);
                    }
                }
            }
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
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable s) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            Editable text = getText();
            if (text == null)
                return;

            TokenImageSpan[] spans = text.getSpans(start, start + count, TokenImageSpan.class);

            for (TokenImageSpan token: spans) {

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

                    if (spanStart >= 0 && text.charAt(spanStart) == ',') {
                        text.delete(spanStart, spanStart + 1);
                    }
                }
            }
        }
    }

    /**
     * On some older versions of android sdk, the onSpanRemoved and onSpanChanged are not reliable
     * this class supplements the TokenSpanWatcher to manually trigger span updates
     */
    private class TokenTextWatcherAPI8 extends TokenTextWatcher {
        private ArrayList<TokenImageSpan> currentTokens = new ArrayList<TokenImageSpan>();

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
            for (TokenImageSpan token: spans) {
                currentTokens.add(token);
            }
        }

        @Override
        public void afterTextChanged(Editable s) {
            TokenImageSpan[] spans = s.getSpans(0, s.length(), TokenImageSpan.class);
            for (TokenImageSpan token: currentTokens) {
                if (!Arrays.asList(spans).contains(token)) {
                    spanWatcher.onSpanRemoved(s, token, s.getSpanStart(token), s.getSpanEnd(token));
                }
            }
        }
    }
}