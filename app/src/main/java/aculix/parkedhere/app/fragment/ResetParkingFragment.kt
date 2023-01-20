package aculix.parkedhere.app.fragment

import aculix.parkedhere.app.databinding.FragmentResetParkingBinding
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ResetParkingFragment : BottomSheetDialogFragment() {

    interface ResetParkingListener {
        fun onConfirmResetParking(bottomSheetDialogFragment: BottomSheetDialogFragment)
    }

    private var _binding: FragmentResetParkingBinding? = null
    private val binding get() = _binding!!

    var resetParkingListener: ResetParkingListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        resetParkingListener = try {
            context as ResetParkingListener
        } catch (e: ClassCastException) {
            throw AssertionError("Must implement NoticeDialogListener", e)
        }
    }

    override fun onDetach() {
        super.onDetach()
        resetParkingListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetParkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onConfirmResetParkingClick()
        onDismissClick()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onConfirmResetParkingClick() {
        binding.btnConfirmResetParking.setOnClickListener {
            resetParkingListener?.onConfirmResetParking(this)
            dismiss()
        }
    }

    private fun onDismissClick() {
        binding.btnDismissResetParking.setOnClickListener {
            dismiss()
        }
    }

    companion object {
        fun createBottomSheetDialog(): BottomSheetDialogFragment {
            return ResetParkingFragment()
        }
    }
}
