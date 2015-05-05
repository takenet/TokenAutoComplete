package com.tokenautocomplete.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tokenautocomplete.TokenCompleteTextView;

import net.take.tokenautocomplete.example.R;

/**
 * Sample token completion view for basic contact info
 *
 * Created on 9/12/13.
 * @author mgod
 */
public class ContactsCompletionView extends TokenCompleteTextView {

    public ContactsCompletionView(Context context) {
        super(context);
    }

    public ContactsCompletionView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ContactsCompletionView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected View getViewForObject(Object object) {
        Person p = (Person)object;

        LayoutInflater l = (LayoutInflater)getContext().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
        LinearLayout view = (LinearLayout)l.inflate(R.layout.contact_token, (ViewGroup)ContactsCompletionView.this.getParent(), false);
        ((TextView)view.findViewById(R.id.name)).setText(p.getEmail());

        return view;
    }

    @Override
    protected Object defaultObject(String completionText) {
        //Stupid simple example of guessing if we have an email or not
        int index = completionText.indexOf('@');
        if (index == -1) {
            return new Person(completionText, completionText.replace(" ", "") + "@example.com");
        } else {
            return new Person(completionText.substring(0, index), completionText);
        }
    }

    @Override
    protected OnTokenClickListener getOnTokenClickListener() {
        return new OnTokenClickListener() {
            @Override
            public void onClick(TokenImageSpan tokenImageSpan) {
            }
        };
    }

    @Override
    protected OnTokenLongClickListener getOnTokenLongClickListener() {
        return new OnTokenLongClickListener() {
            @Override
            public void onLongClick(TokenImageSpan tokenImageSpan) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

                final Person person = (Person)tokenImageSpan.getToken();

                Button openMenu = new Button(getContext());
                openMenu.setText(person.getEmail());
                //openMenu.setBackgroundColor( 0xFFFFFFFF );

                builder.setTitle(person.getName());
                builder.setInverseBackgroundForced(true);
                builder.setView(openMenu);
                final Dialog dialog = builder.create();

                openMenu.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();

                        Intent intent = new Intent(Intent.ACTION_SENDTO);
                        intent.setType("message/rfc822");
                        intent.putExtra(Intent.EXTRA_EMAIL, person.getEmail());
                        intent.putExtra(Intent.EXTRA_SUBJECT, "Subject");
                        intent.putExtra(Intent.EXTRA_TEXT, "I'm email body.");

                        getContext().startActivity(Intent.createChooser(intent, "Send Email"));
                    }
                });


                dialog.show();
                System.out.println("OPEN DIALOG ------------------------------------- " + dialog);
            }
        };
    }
}
