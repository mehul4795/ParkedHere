package aculix.parkedhere.app.fragment

import aculix.parkedhere.app.R
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton

/** A DialogFragment for the Privacy Notice Dialog Box.  */
class PrivacyNoticeDialogFragment : DialogFragment() {
    /** Listener for a privacy notice response.  */
    interface NoticeDialogListener {
        /** Invoked when the user accepts sharing experience.  */
        fun onDialogPositiveClick(dialog: DialogFragment?)
    }

    var noticeDialogListener: NoticeDialogListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        noticeDialogListener = try {
            context as NoticeDialogListener
        } catch (e: ClassCastException) {
            throw AssertionError("Must implement NoticeDialogListener", e)
        }
    }

    override fun onDetach() {
        super.onDetach()
        noticeDialogListener = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
        val customLayout = layoutInflater.inflate(R.layout.fragment_privacy_notice_dialog, null)
        builder.setView(customLayout).setCancelable(false)

        // Learn More Button
        customLayout.findViewById<MaterialButton>(R.id.btnLearnMore).setOnClickListener {
            val browserIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.learn_more_url)))
            requireActivity().startActivity(browserIntent)
        }

        // Get Started Button
        customLayout.findViewById<MaterialButton>(R.id.btnGetStarted)
            .setOnClickListener { // Send the positive button event back to the host activity
                this.dismiss()
                noticeDialogListener!!.onDialogPositiveClick(this)
            }

        return builder.create()
    }

    companion object {
        fun createDialog(): PrivacyNoticeDialogFragment {
            return PrivacyNoticeDialogFragment()
        }
    }
}
