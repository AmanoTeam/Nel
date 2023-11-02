package com.amanoteam.nel;

import android.app.Activity;
import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findField;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class Nel implements IXposedHookLoadPackage {
	
	public void handleLoadPackage(final LoadPackageParam loadPackageParam) throws Throwable {
		
		if (!loadPackageParam.packageName.equals("com.android.certinstaller")) {
			return;
		}
		
		findAndHookMethod("com.android.certinstaller.CertInstaller", loadPackageParam.classLoader, "installCertificateToKeystore", Context.class, new XC_MethodHook() {
			
			@Override
			protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
				final Activity activity = (Activity) param.thisObject;
				final Context context = (Context) param.args[0];
				
				final Field field = findField(activity.getClass(), "mCredentials");
				final Object credentialHelper = field.get(activity);
				
				final Class credentialsClass = findClass("android.security.Credentials", loadPackageParam.classLoader);
				final Field subfield = findField(credentialsClass, "CERTIFICATE_USAGE_CA");
				final String certificateUsageCa = (String) subfield.get(null);
				
				final String certUsage = (String) callMethod(credentialHelper, "getCertUsageSelected");
				
				if (!certUsage.equals(certificateUsageCa)) {
					return;
				}
				
				callMethod(credentialHelper, "createSystemInstallIntent", context);
				param.setResult(null);
				
				activity.setResult(activity.RESULT_OK);
				activity.finish();
			}
			
		});
		
		findAndHookMethod("com.android.certinstaller.CredentialHelper", loadPackageParam.classLoader, "createSystemInstallIntent", Context.class, new XC_MethodHook() {
			
			@Override
			protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
				final Class credentialsClass = findClass("android.security.Credentials", loadPackageParam.classLoader);
				final Field field = findField(credentialsClass, "CERTIFICATE_USAGE_CA");
				final String certificateUsageCa = (String) field.get(null);
				
				final Object credentialHelper = param.thisObject;
				final String certUsage = (String) callMethod(credentialHelper, "getCertUsageSelected");
				
				if (!certUsage.equals(certificateUsageCa)) {
					return;
				}
				
				final Context context = (Context) param.args[0];
				
				final Field subfield = findField(credentialsClass, "EXTRA_CA_CERTIFICATES_DATA");
				final String extraCaCertificatesData = (String) subfield.get(null);
				
				final Intent intent = (Intent) param.getResult();
				final byte[] bytes = intent.getByteArrayExtra(extraCaCertificatesData);
				
				final Class keyChainClass = findClass("android.security.KeyChain", loadPackageParam.classLoader);
				
				final Class trustedCertificateStoreClass = findClass("com.android.org.conscrypt.TrustedCertificateStore", loadPackageParam.classLoader);
				final Object trustedCertificateStore = trustedCertificateStoreClass.getDeclaredConstructor().newInstance();
				
				new Thread(() -> {
					final Object keyChainConnection = callStaticMethod(keyChainClass, "bind", context);
					final Object keyChainService = callMethod(keyChainConnection, "getService");
					callMethod(keyChainService, "installCaCertificate", bytes);
					
					CertificateFactory certificateFactory = null;
					
					try {
						certificateFactory = CertificateFactory.getInstance("X.509");
					} catch (final CertificateException e) {}
					
					X509Certificate certificate = null;
					
					try {
						certificate = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(bytes));
					} catch (final CertificateException e) {}
					
					final String alias = (String) callMethod(trustedCertificateStore, "getCertificateAlias", certificate);
					
					final int userHandle = (int) callStaticMethod(UserHandle.class, "myUserId");
					
					final DevicePolicyManager devicePolicyManager = context.getSystemService(DevicePolicyManager.class);
					callMethod((Object) devicePolicyManager, "approveCaCert", alias, userHandle, true);
					
					new Handler(Looper.getMainLooper()).post(() -> {
						final Resources resources = context.getResources();
						final String packageName = context.getPackageName();
						
						final int ca_cert_is_added = resources.getIdentifier("ca_cert_is_added", "string", packageName);
						final String text = resources.getString(ca_cert_is_added);
						
						Toast.makeText(context, text, Toast.LENGTH_LONG).show();
					});
				}).start();
				
			}
			
		});
		
		findAndHookMethod("com.android.certinstaller.CertInstaller", loadPackageParam.classLoader, "createRedirectCaCertificateDialog", new XC_MethodHook() {
			
			@Override
			protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
				final Activity activity = (Activity) param.thisObject;
				final Resources resources = activity.getResources();
				final String packageName = activity.getPackageName();
				
				final Dialog dialog = (Dialog) callMethod((Object) activity, "createSelectCertificateUsageDialog");
				
				Field field = null;
				
				field = findField(dialog.getClass(), "mAlert");
				final Object mAlert = field.get(dialog);
				
				field = findField(mAlert.getClass(), "mView");
				final View view = (View) field.get(mAlert);
				
				field = findField(activity.getClass(), "mCredentials");
				final Object credentials = field.get(activity);
				
				final int certificate_usage = resources.getIdentifier("certificate_usage", "id", packageName);
				final int wifi_certificate = resources.getIdentifier("wifi_certificate", "id", packageName);
				final int user_certificate = resources.getIdentifier("user_certificate", "id", packageName);
				
				final int select_certificate_usage_dialog = resources.getIdentifier("select_certificate_usage_dialog", "layout", packageName);
				
				final int ca_certificate = resources.getIdentifier("ca_certificate", "string", packageName);
				
				final LayoutInflater layoutInflater = activity.getLayoutInflater();
				final View root = layoutInflater.inflate(select_certificate_usage_dialog, null);
				
				final RadioButton button = (RadioButton) root.findViewById(wifi_certificate);
				button.setId(View.generateViewId());
				
				final String text = resources.getString(ca_certificate);
				button.setText(text);
				
				final RadioGroup otherGroup = (RadioGroup) root.findViewById(certificate_usage);
				otherGroup.removeAllViews();
				
				final RadioGroup group = view.findViewById(certificate_usage);
				group.addView(button);
				
				group.setOnCheckedChangeListener((gp, checkedId) -> {
					final Class credentialsClass = findClass("android.security.Credentials", loadPackageParam.classLoader);
					
					if (checkedId == button.getId()) {
						final Field subfield = findField(credentialsClass, "CERTIFICATE_USAGE_CA");
						Object value = null;
						
						try {
							value = subfield.get(null);
						} catch (final IllegalAccessException e) {}
						
						callMethod(credentials, "setCertUsageSelectedAndUid", value);
						
						return;
					}
					
					if (checkedId == user_certificate) {
						final Field subfield = findField(credentialsClass, "CERTIFICATE_USAGE_USER");
						Object value = null;
						
						try {
							value = subfield.get(null);
						} catch (final IllegalAccessException e) {}
						
						callMethod(credentials, "setCertUsageSelectedAndUid", value);
						
						return;
					}
					
					if (checkedId == wifi_certificate) {
						final Field subfield = findField(credentialsClass, "CERTIFICATE_USAGE_WIFI");
						Object value = null;
						
						try {
							value = subfield.get(null);
						} catch (final IllegalAccessException e) {}
						
						callMethod(credentials, "setCertUsageSelectedAndUid", value);
						
						return;
					}
				});
				
				param.setResult((Object) dialog);
			}
			
		});
		
	}
	
}
