package com.limpe.tengerium.ui

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.databinding.FragmentAddContactBinding
import kotlinx.coroutines.launch

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            if (!findNavController().navigateUp()) {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performAdd()
                true
            } else false
        }

        binding.btnAdd.setOnClickListener {
            performAdd()
        }
    }

    private fun performAdd() {
        val email = binding.etEmail.text.toString().trim()
        
        if (email.isEmpty()) {
            binding.tilEmail.error = getString(R.string.fill_all_fields)
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.invalid_email)
            return
        }

        binding.tilEmail.error = null
        binding.btnAdd.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val repository = (requireActivity().application as TengeriumApp).repository
        repository.addContact(email) { error ->
            viewLifecycleOwner.lifecycleScope.launch {
                val currentBinding = _binding ?: return@launch
                currentBinding.btnAdd.isEnabled = true
                currentBinding.progressBar.visibility = View.GONE
                if (error == null) {
                    Toast.makeText(requireContext(), getString(R.string.contact_add_success, email), Toast.LENGTH_LONG).show()
                    findNavController().navigateUp()
                } else {
                    currentBinding.tilEmail.error = getString(R.string.contact_add_failed, error)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
