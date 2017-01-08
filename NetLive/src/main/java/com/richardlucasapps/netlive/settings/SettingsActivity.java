package com.richardlucasapps.netlive.settings;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.richardlucasapps.netlive.R;
import com.richardlucasapps.netlive.gauge.GaugeService;

public class SettingsActivity extends AppCompatActivity {
  private static final int READ_PHONE_STATE_REQUEST = 37;

  private AlertDialog aboutDialog;
  private AlertDialog helpDialog;
  private AlertDialog welcomeDialog;

  @Override protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    if (Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
      PreferenceManager.setDefaultValues(this, R.xml.preferences_for_jelly_bean_mr2, false);
    } else {
      PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      fillStats();
    }

    boolean firstRun =
        getSharedPreferences("START_UP_PREFERENCE", MODE_PRIVATE).getBoolean("firstRun", true);

    if (firstRun) {
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setPositiveButton(getString(R.string.welcome_message_dismiss),
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              // User clicked OK button
            }
          });
      builder.setMessage(getString(R.string.welcome) + getString(R.string.welcome_para))
          .setTitle(getString(R.string.welcome_message_message) + " " + getString(
              R.string.app_name_with_version_number));

      welcomeDialog = builder.create();
      welcomeDialog.show();

      getSharedPreferences("START_UP_PREFERENCE", MODE_PRIVATE).edit()
          .putBoolean("firstRun", false)
          .commit();
    }
    Intent intent = new Intent(getApplicationContext(), GaugeService.class); //getApp
    startService(intent);
  }

  @Override protected void onStart() {
    super.onStart();
    requestPermissions();
  }

  @Override @TargetApi(Build.VERSION_CODES.M) protected void onResume() {
    super.onResume();
    if (!hasPermissions()) {
      return;
    }
  }

  private void requestPermissions() {
    if (!hasPermissionToReadNetworkHistory()) {
      return;
    }
    if (!hasPermissionToReadPhoneStats()) {
      requestPhoneStateStats();
      return;
    }
  }

  private boolean hasPermissions() {
    return hasPermissionToReadNetworkHistory() && hasPermissionToReadPhoneStats();
  }

  private boolean hasPermissionToReadPhoneStats() {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        == PackageManager.PERMISSION_DENIED) {
      return false;
    } else {
      return true;
    }
  }

  private void requestPhoneStateStats() {
    ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.READ_PHONE_STATE },
        READ_PHONE_STATE_REQUEST);
  }

  private boolean hasPermissionToReadNetworkHistory() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }
    final AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
    int mode =
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            getPackageName());
    if (mode == AppOpsManager.MODE_ALLOWED) {
      return true;
    }
    appOps.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
        getApplicationContext().getPackageName(), new AppOpsManager.OnOpChangedListener() {
          @Override @TargetApi(Build.VERSION_CODES.M)
          public void onOpChanged(String op, String packageName) {
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
              return;
            }
            appOps.stopWatchingMode(this);
            Intent intent = new Intent(SettingsActivity.this, SettingsActivity.class);
            if (getIntent().getExtras() != null) {
              intent.putExtras(getIntent().getExtras());
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(intent);
          }
        });
    requestReadNetworkHistoryAccess();
    return false;
  }

  private void requestReadNetworkHistoryAccess() {
    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    startActivity(intent);
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override protected void onPause() {
    super.onPause();
    if (aboutDialog != null) {
      aboutDialog.dismiss();
    }
    if (helpDialog != null) {
      helpDialog.dismiss();
    }
    if (welcomeDialog != null) {
      welcomeDialog.dismiss();
    }
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    // Handle item selection
    switch (item.getItemId()) {
      case R.id.action_settings_rate_netlive:
        openNetLiveInGooglePlay();
        return true;
      case R.id.action_settings_share:

        Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
        sharingIntent.setType("text/plain");
        String url = getString(R.string.app_url_if_uri_fails);
        String shareBody = getString(R.string.netlive_share_body) + " " + url;
        sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.app_name));
        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareBody);
        startActivity(Intent.createChooser(sharingIntent, getString(R.string.share_via)));
        return true;

      case R.id.action_settings_help:
        showHelpDialog();
        return true;

      case R.id.action_settings_about:
        showAboutDialog();
        return true;

      case R.id.action_settings_send_feedback:
        Intent Email = new Intent(Intent.ACTION_SEND);
        Email.setType("message/rfc822");
        Email.putExtra(Intent.EXTRA_EMAIL, new String[] { "richardlucasapps@gmail.com" });
        Email.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.feedback)); //feedback
        startActivity(Intent.createChooser(Email, getString(R.string.send_feedback)));
        return true;
      case android.R.id.home:
        startActivity(new Intent(SettingsActivity.this, SettingsActivity.class));
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  private void openNetLiveInGooglePlay() {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    //Try Google play
    intent.setData(Uri.parse(getString(R.string.app_uri_for_google_play)));
    if (noActivityFoundForIntent(intent)) {
      //Market (Google play) app seems not installed, let's try to open a webbrowser
      intent.setData(Uri.parse(getString(R.string.app_url_if_uri_fails)));
      if (noActivityFoundForIntent(intent)) {
        //Well if this also fails, we have run out of options, inform the user.
        Toast.makeText(this, getString(R.string.could_not_open_app_in_Google_play),
            Toast.LENGTH_SHORT).show();
      }
    }
  }

  private boolean noActivityFoundForIntent(Intent aIntent) {
    try {
      startActivity(aIntent);
      return false;
    } catch (ActivityNotFoundException e) {
      return true;
    }
  }

  private void showAboutDialog() {
    AlertDialog.Builder aboutBuilder = new AlertDialog.Builder(this);
    TextView myMsg = new TextView(this);
    String version;
    try {
      PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
      version = Integer.toString(packageInfo.versionCode);
    } catch (PackageManager.NameNotFoundException e) {
      version = getString(R.string.version_code_not_found);
    }
    SpannableString s = new SpannableString(getString(R.string.app_name_with_version_number)
        +
        "\n\n"
        + " "
        + getString(R.string.heading_version_code)
        + " "
        + version
        + "\n\nrichardlucasapps.com");
    Linkify.addLinks(s, Linkify.WEB_URLS);
    myMsg.setText(s);
    myMsg.setTextSize(15);
    myMsg.setMovementMethod(LinkMovementMethod.getInstance());
    myMsg.setGravity(Gravity.CENTER);
    aboutBuilder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        // User clicked OK button
      }
    });
    aboutBuilder.setView(myMsg).setTitle(getString(R.string.about));

    aboutDialog = aboutBuilder.create();
    aboutDialog.show();
  }

  private void showHelpDialog() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    String s = getString(R.string.help_dialog_para_1);

    String overviewTitle = getString(R.string.overview);
    String overviewContent = getString(R.string.help_dialog_para_2);
    String batteryLifeTitle = getString(R.string.battery_life_help_title);
    String batteryLifeAdvice = getString(R.string.battery_life_help_advice);

    String androidMR2Title = getString(R.string.help_dialog_android_jelly_bean_mr2_title);
    String androidMR2Body = getString(R.string.help_dialog_android_jelly_bean_mr2_body);

    String si = getString(R.string.help_dialog_para_3);

    LayoutInflater inflater = LayoutInflater.from(this);
    @SuppressLint("InflateParams") View view = inflater.inflate(R.layout.help_dialog, null);
    TextView textview = (TextView) view.findViewById(R.id.textmsg);
    textview.setText((Html.fromHtml(s
        + "<br>"
        + "<br>"
        + "<b>"
        + overviewTitle
        + "</b>"
        + "<br>"
        + "<br>"
        + overviewContent
        + "<br>"
        + "<br>"
        + si
        + "<br>"
        + "<br>"
        + "<b>"
        + batteryLifeTitle
        + "</b>"
        + "<br>"
        + "<br>"
        + batteryLifeAdvice
        + "<b>"
        + "<br>"
        + "<br>"
        + androidMR2Title
        + "</b>"
        + "<br>"
        + "<br>"
        + androidMR2Body
        + "<a href=\"https://code.google.com/p/android/issues/detail?id=58210\">https://code.google.com/p/android/issues/detail?id=58210</a>")));

    textview.setTextSize(17);
    textview.setPadding(15, 15, 15, 15);
    textview.setMovementMethod(LinkMovementMethod.getInstance());

    builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        // User clicked OK button
      }
    });
    builder.setView(view)
        .setTitle(getString(R.string.welcome_message_message) + " " + getString(
            R.string.app_name_with_version_number));

    helpDialog = builder.create();
    helpDialog.show();
  }

  private static final int MY_PERMISSIONS_REQUEST_PACKAGE_USAGE_STATS = 100;

  private void fillStats() {
    if (hasPermission()) {
      //getStats();
    } else {
      requestPermission();
    }
  }

  @TargetApi(Build.VERSION_CODES.N) private boolean hasPermission() {
    AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);

    int mode =
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            getPackageName());

    return mode == AppOpsManager.MODE_ALLOWED;
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case MY_PERMISSIONS_REQUEST_PACKAGE_USAGE_STATS:
        if (hasPermission()) {
          //getStats();
        } else {
          requestPermission();
        }
        break;
    }
  }

  private void requestPermission() {
    Toast.makeText(this, "Need to request permission", Toast.LENGTH_SHORT).show();
    startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        MY_PERMISSIONS_REQUEST_PACKAGE_USAGE_STATS);
  }
}
