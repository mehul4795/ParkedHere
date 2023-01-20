package aculix.parkedhere.app.fragment

import aculix.parkedhere.app.R
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton

/** A DialogFragment for the VPS availability Notice Dialog Box.  */
class VpsAvailabilityNoticeDialogFragment : DialogFragment() {

    /** Listener for a VPS availability notice response.  */
    interface NoticeDialogListener {
        /** Invoked when the user accepts sharing experience.  */
        fun onDialogContinueClick(dialog: DialogFragment?)
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
        val customLayout = layoutInflater.inflate(R.layout.fragment_vps_availability_dialog, null)
        builder.setView(customLayout).setCancelable(false)

        // Continue Button
        customLayout.findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener { // Send the positive button event back to the host activity
            noticeDialogListener!!.onDialogContinueClick(this)
            dismiss()
        }

        val dialog: Dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    companion object {
        fun createDialog(): VpsAvailabilityNoticeDialogFragment {
            return VpsAvailabilityNoticeDialogFragment()
        }
    }
}
