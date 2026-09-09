@file:Suppress("DEPRECATION")
package com.gamervoice.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gamervoice.app.auth.AuthManager
import com.gamervoice.app.databinding.ActivityAuthBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@Suppress("DEPRECATION")
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var isSignUpMode = false
    private var selectedAvatar = "avatar_1"
    private lateinit var avatarViews: List<ImageView>
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                setLoading(true)
                AuthManager.signInWithGoogle(account) { res ->
                    setLoading(false)
                    res.onSuccess { user ->
                        val gEmail = user.email.ifBlank { account.email.orEmpty() }
                        val gName = user.name.ifBlank { account.displayName.orEmpty() }

                        // Strict Rule: If welcome email has NOT been sent to this user yet (e.g. SMTP was pending or new user),
                        // send it now. Once delivered, it will never send again.
                        if (!com.gamervoice.app.util.WelcomeEmailHelper.hasWelcomeBeenSent(this, gEmail)) {
                            com.gamervoice.app.util.WelcomeEmailHelper.sendWelcomeEmailOnce(this, gEmail, gName, user.uid)
                        } else {
                            android.util.Log.i("AuthActivity", "Welcome email already delivered to $gEmail previously. Suppressed.")
                        }
                        navigateToHome()
                    }.onFailure { err ->
                        showError(err.message ?: "Google sign-in failed")
                    }
                }
            } else {
                showError("Google sign-in was cancelled.")
            }
        } catch (e: ApiException) {
            // Status 12501 is user cancelled, 10 is developer config/SHA1
            if (e.statusCode == 12501) {
                showError("Google sign-in cancelled.")
            } else {
                showError("Google sign-in error (Code: ${e.statusCode})")
            }
        } catch (e: Exception) {
            showError("Google sign-in error: ${e.localizedMessage}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AuthManager.init(this)
        if (AuthManager.isLoggedIn()) {
            navigateToHome()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        avatarViews = listOf(
            binding.ivAvatar1,
            binding.ivAvatar2,
            binding.ivAvatar3,
            binding.ivAvatar4,
            binding.ivAvatar5
        )

        setupTabs()
        setupAvatarPicker()
        setupSubmitButton()
        setupGoogleSignIn()
    }

    private fun setupGoogleSignIn() {
        binding.btnGoogleSignIn.setOnClickListener {
            // Clear prior sign in cache to allow choosing account
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            }
        }
    }

    private fun setupTabs() {
        binding.tabSignIn.setOnClickListener {
            setMode(false)
        }

        binding.tabSignUp.setOnClickListener {
            setMode(true)
        }
    }

    private fun setMode(signUp: Boolean) {
        isSignUpMode = signUp
        binding.tvAuthError.visibility = View.GONE

        val colorGreen = ContextCompat.getColor(this, R.color.accent_green)
        val colorDark = ContextCompat.getColor(this, R.color.black)
        val colorMuted = ContextCompat.getColor(this, R.color.text_secondary)
        val colorTransparent = ContextCompat.getColor(this, android.R.color.transparent)

        if (signUp) {
            binding.tabSignUp.backgroundTintList = ColorStateList.valueOf(colorGreen)
            binding.tabSignUp.setTextColor(colorDark)
            binding.tabSignIn.backgroundTintList = ColorStateList.valueOf(colorTransparent)
            binding.tabSignIn.setTextColor(colorMuted)

            binding.tilName.visibility = View.VISIBLE
            binding.tilPhone.visibility = View.VISIBLE
            binding.llAvatarSection.visibility = View.VISIBLE
            binding.btnSubmitAuth.text = "CREATE ACCOUNT ⚡"
        } else {
            binding.tabSignIn.backgroundTintList = ColorStateList.valueOf(colorGreen)
            binding.tabSignIn.setTextColor(colorDark)
            binding.tabSignUp.backgroundTintList = ColorStateList.valueOf(colorTransparent)
            binding.tabSignUp.setTextColor(colorMuted)

            binding.tilName.visibility = View.GONE
            binding.tilPhone.visibility = View.GONE
            binding.llAvatarSection.visibility = View.GONE
            binding.btnSubmitAuth.text = "SIGN IN ⚡"
        }
    }

    private fun setupAvatarPicker() {
        avatarViews.forEachIndexed { index, iv ->
            val avatarKey = "avatar_${index + 1}"
            iv.setOnClickListener {
                selectedAvatar = avatarKey
                updateAvatarSelectionUI()
            }
        }
        updateAvatarSelectionUI()
    }

    private fun updateAvatarSelectionUI() {
        avatarViews.forEachIndexed { index, iv ->
            val avatarKey = "avatar_${index + 1}"
            if (avatarKey == selectedAvatar) {
                iv.setBackgroundResource(R.drawable.bg_avatar_ring)
                iv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                iv.background = null
                iv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmitAuth.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            if (email.isEmpty() || !email.contains("@")) {
                showError("Please enter a valid email address.")
                return@setOnClickListener
            }

            if (password.length < 6) {
                showError("Password must be at least 6 characters.")
                return@setOnClickListener
            }

            if (isSignUpMode) {
                val name = binding.etName.text?.toString()?.trim().orEmpty()
                val phone = binding.etPhone.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    showError("Please enter your gamer/in-game name.")
                    return@setOnClickListener
                }

                setLoading(true)
                AuthManager.signUp(email, password, name, phone, selectedAvatar) { result ->
                    setLoading(false)
                    result.onSuccess { user ->
                        // Strict Rule: Sends welcome email strictly ONCE for new account creation
                        com.gamervoice.app.util.WelcomeEmailHelper.sendWelcomeEmailOnce(this, email, name, user.uid)
                        navigateToHome()
                    }.onFailure { err ->
                        showError(err.message ?: "Failed to create account")
                    }
                }
            } else {
                setLoading(true)
                AuthManager.signIn(email, password) { result ->
                    setLoading(false)
                    result.onSuccess { user ->
                        if (!com.gamervoice.app.util.WelcomeEmailHelper.hasWelcomeBeenSent(this, user.email)) {
                            com.gamervoice.app.util.WelcomeEmailHelper.sendWelcomeEmailOnce(this, user.email, user.name, user.uid)
                        }
                        navigateToHome()
                    }.onFailure { err ->
                        showError(err.message ?: "Sign in failed")
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnSubmitAuth.isEnabled = !loading
        binding.pbAuthLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            binding.tvAuthError.visibility = View.GONE
        }
    }

    private fun showError(msg: String) {
        binding.tvAuthError.text = msg
        binding.tvAuthError.visibility = View.VISIBLE
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
