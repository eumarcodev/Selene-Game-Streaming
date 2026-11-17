// Decodes PyroWave frames on the GPU and presents them on an Android Surface.
//
// PyroWave owns the Vulkan instance and device (created with the surface and swapchain extensions);
// this file borrows the handles to create a swapchain and a small render pass that converts the
// decoded YCbCr planes to RGB. PyroWave only records commands into our command buffer, we own
// submission and presentation.
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/system_properties.h>
#include <chrono>
#include <mutex>
#include <vector>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include "volk.h"
#include "pyrowave.h"

#define LOG_TAG "SelenePyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace
{
const uint32_t kVertSpv[] =
#include "shaders/present_vert.inc"
;
const uint32_t kFragSpv[] =
#include "shaders/present_frag.inc"
;

struct Plane
{
	VkImage image = VK_NULL_HANDLE;
	VkDeviceMemory memory = VK_NULL_HANDLE;
	VkImageView view = VK_NULL_HANDLE;
	uint32_t width = 0, height = 0;
};

// Sums over the frames presented since the last read, in nanoseconds.
struct StageTotals
{
	int64_t parse = 0;      // bitstream parse (push_packet)
	int64_t fence_wait = 0; // wait for the previous frame to finish on the GPU
	int64_t acquire = 0;    // vkAcquireNextImageKHR
	int64_t record = 0;     // command recording, the decoder commands included
	int64_t submit = 0;     // vkQueueSubmit
	int64_t present = 0;    // vkQueuePresentKHR
	uint32_t frames = 0;
	int64_t gpu_decode = 0;  // GPU time of the decode
	int64_t gpu_present = 0; // GPU time of the YCbCr to RGB pass
	uint32_t gpu_frames = 0;
};

// What one frame needs while it is on the GPU. With two of them the next frame is recorded while the
// previous one is still being decoded, so the GPU does not sit idle during the CPU work.
struct FrameSlot
{
	VkCommandBuffer cmd = VK_NULL_HANDLE;
	VkFence fence = VK_NULL_HANDLE;
	VkSemaphore acquire_sem = VK_NULL_HANDLE, render_sem = VK_NULL_HANDLE;
	// GPU time of the decode and of the presentation pass, from timestamp queries (when supported).
	VkQueryPool query_pool = VK_NULL_HANDLE;
	bool fence_pending = false;
	bool query_pending = false;
};
const int kFrameSlots = 2;

struct Player
{
	ANativeWindow *window = nullptr;
	int width = 0, height = 0;
	bool chroma444 = false;

	pyrowave_device device = nullptr;
	pyrowave_decoder decoder = nullptr;
	pyrowave_encoder test_encoder = nullptr; // only for the on-device self test
	bool fragment_path = false;

	VkInstance instance = VK_NULL_HANDLE;
	VkPhysicalDevice gpu = VK_NULL_HANDLE;
	VkDevice vk = VK_NULL_HANDLE;
	uint32_t family = 0;
	VkQueue queue = VK_NULL_HANDLE;

	VkSurfaceKHR surface = VK_NULL_HANDLE;
	VkSwapchainKHR swapchain = VK_NULL_HANDLE;
	VkFormat sc_format = VK_FORMAT_UNDEFINED;
	VkExtent2D sc_extent = {};
	std::vector<VkImage> sc_images;
	std::vector<VkImageView> sc_views;
	std::vector<VkFramebuffer> sc_fbs;

	VkRenderPass render_pass = VK_NULL_HANDLE;
	VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
	VkPipelineLayout pipe_layout = VK_NULL_HANDLE;
	VkPipeline pipeline = VK_NULL_HANDLE;
	VkDescriptorPool desc_pool = VK_NULL_HANDLE;
	VkDescriptorSet desc_set = VK_NULL_HANDLE;
	VkSampler sampler = VK_NULL_HANDLE;
	VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;

	Plane planes[3];

	VkCommandPool pool = VK_NULL_HANDLE;
	FrameSlot slots[kFrameSlots];
	int next_slot = 0;
	bool planes_initialized = false;

	uint64_t frames_presented = 0;
	uint64_t frames_dropped = 0;

	bool timestamps = false;
	uint64_t timestamp_mask = 0;
	double timestamp_period_ns = 0.0;

	// Time spent in each stage since the last nativeStageStats call. Written by the decoder thread,
	// read by the overlay timer.
	std::mutex stats_lock;
	StageTotals stages;
};

int64_t now_ns()
{
	return std::chrono::duration_cast<std::chrono::nanoseconds>(
		std::chrono::steady_clock::now().time_since_epoch()).count();
}

const char *present_mode_name(VkPresentModeKHR mode)
{
	switch (mode)
	{
	case VK_PRESENT_MODE_IMMEDIATE_KHR: return "immediate";
	case VK_PRESENT_MODE_MAILBOX_KHR: return "mailbox";
	case VK_PRESENT_MODE_FIFO_KHR: return "fifo";
	case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "fifo relaxed";
	default: return "other";
	}
}

uint32_t find_memory_type(VkPhysicalDevice gpu, uint32_t bits, VkMemoryPropertyFlags want)
{
	VkPhysicalDeviceMemoryProperties props;
	vkGetPhysicalDeviceMemoryProperties(gpu, &props);
	for (uint32_t i = 0; i < props.memoryTypeCount; i++)
		if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & want) == want)
			return i;
	return UINT32_MAX;
}

bool create_plane(Player &p, Plane &pl, uint32_t w, uint32_t h, VkImageUsageFlags usage)
{
	pl.width = w;
	pl.height = h;

	VkImageCreateInfo ici = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
	ici.imageType = VK_IMAGE_TYPE_2D;
	ici.format = VK_FORMAT_R8_UNORM;
	ici.extent = { w, h, 1 };
	ici.mipLevels = 1;
	ici.arrayLayers = 1;
	ici.samples = VK_SAMPLE_COUNT_1_BIT;
	ici.tiling = VK_IMAGE_TILING_OPTIMAL;
	ici.usage = usage;
	ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
	ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	if (vkCreateImage(p.vk, &ici, nullptr, &pl.image) != VK_SUCCESS)
		return false;

	VkMemoryRequirements req;
	vkGetImageMemoryRequirements(p.vk, pl.image, &req);
	VkMemoryAllocateInfo mai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
	mai.allocationSize = req.size;
	mai.memoryTypeIndex = find_memory_type(p.gpu, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
	if (mai.memoryTypeIndex == UINT32_MAX)
		return false;
	if (vkAllocateMemory(p.vk, &mai, nullptr, &pl.memory) != VK_SUCCESS)
		return false;
	if (vkBindImageMemory(p.vk, pl.image, pl.memory, 0) != VK_SUCCESS)
		return false;

	VkImageViewCreateInfo vci = { VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
	vci.image = pl.image;
	vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
	vci.format = VK_FORMAT_R8_UNORM;
	vci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
	return vkCreateImageView(p.vk, &vci, nullptr, &pl.view) == VK_SUCCESS;
}

void destroy_swapchain(Player &p)
{
	for (auto fb : p.sc_fbs) vkDestroyFramebuffer(p.vk, fb, nullptr);
	for (auto v : p.sc_views) vkDestroyImageView(p.vk, v, nullptr);
	p.sc_fbs.clear();
	p.sc_views.clear();
	p.sc_images.clear();
	if (p.swapchain)
	{
		vkDestroySwapchainKHR(p.vk, p.swapchain, nullptr);
		p.swapchain = VK_NULL_HANDLE;
	}
}

bool create_swapchain(Player &p)
{
	VkSurfaceCapabilitiesKHR caps;
	if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(p.gpu, p.surface, &caps) != VK_SUCCESS)
		return false;

	VkExtent2D extent = caps.currentExtent;
	if (extent.width == 0xffffffffu)
		extent = { uint32_t(ANativeWindow_getWidth(p.window)), uint32_t(ANativeWindow_getHeight(p.window)) };
	if (extent.width == 0 || extent.height == 0)
		return false;

	uint32_t count = 0;
	vkGetPhysicalDeviceSurfaceFormatsKHR(p.gpu, p.surface, &count, nullptr);
	std::vector<VkSurfaceFormatKHR> formats(count);
	vkGetPhysicalDeviceSurfaceFormatsKHR(p.gpu, p.surface, &count, formats.data());
	// The decoded values are already gamma encoded, so use a UNORM format and skip the sRGB conversion.
	VkSurfaceFormatKHR chosen = formats.empty() ? VkSurfaceFormatKHR { VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR } : formats[0];
	for (auto &f : formats)
		if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
		    f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
		{
			chosen = f;
			break;
		}

	// Lowest latency mode that the surface offers.
	VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
	vkGetPhysicalDeviceSurfacePresentModesKHR(p.gpu, p.surface, &count, nullptr);
	std::vector<VkPresentModeKHR> modes(count);
	vkGetPhysicalDeviceSurfacePresentModesKHR(p.gpu, p.surface, &count, modes.data());
	for (auto m : modes)
		if (m == VK_PRESENT_MODE_MAILBOX_KHR) { mode = m; break; }

	VkSwapchainCreateInfoKHR sci = { VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR };
	sci.surface = p.surface;
	sci.minImageCount = caps.minImageCount < 3 ? 3 : caps.minImageCount;
	if (caps.maxImageCount && sci.minImageCount > caps.maxImageCount)
		sci.minImageCount = caps.maxImageCount;
	sci.imageFormat = chosen.format;
	sci.imageColorSpace = chosen.colorSpace;
	sci.imageExtent = extent;
	sci.imageArrayLayers = 1;
	sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
	sci.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
	if (!(caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR))
		sci.preTransform = caps.currentTransform;
	sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
		? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
	sci.presentMode = mode;
	sci.clipped = VK_TRUE;
	sci.oldSwapchain = VK_NULL_HANDLE;

	destroy_swapchain(p);
	if (vkCreateSwapchainKHR(p.vk, &sci, nullptr, &p.swapchain) != VK_SUCCESS)
		return false;
	p.sc_format = chosen.format;
	p.sc_extent = extent;
	LOGI("Swapchain: present mode %s (%u offered), %u images requested, pre-transform 0x%x (current 0x%x), format %d",
	     present_mode_name(mode), unsigned(modes.size()), sci.minImageCount, unsigned(sci.preTransform),
	     unsigned(caps.currentTransform), int(chosen.format));

	vkGetSwapchainImagesKHR(p.vk, p.swapchain, &count, nullptr);
	p.sc_images.resize(count);
	vkGetSwapchainImagesKHR(p.vk, p.swapchain, &count, p.sc_images.data());

	// The render pass depends on the swapchain format, so (re)create it here.
	if (p.render_pass)
	{
		vkDestroyRenderPass(p.vk, p.render_pass, nullptr);
		p.render_pass = VK_NULL_HANDLE;
	}
	VkAttachmentDescription att = {};
	att.format = p.sc_format;
	att.samples = VK_SAMPLE_COUNT_1_BIT;
	att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
	att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
	att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
	att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
	att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
	VkAttachmentReference ref = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
	VkSubpassDescription sub = {};
	sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
	sub.colorAttachmentCount = 1;
	sub.pColorAttachments = &ref;
	VkSubpassDependency dep = {};
	dep.srcSubpass = VK_SUBPASS_EXTERNAL;
	dep.dstSubpass = 0;
	dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
	dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
	dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
	VkRenderPassCreateInfo rpci = { VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO };
	rpci.attachmentCount = 1;
	rpci.pAttachments = &att;
	rpci.subpassCount = 1;
	rpci.pSubpasses = &sub;
	rpci.dependencyCount = 1;
	rpci.pDependencies = &dep;
	if (vkCreateRenderPass(p.vk, &rpci, nullptr, &p.render_pass) != VK_SUCCESS)
		return false;

	for (auto img : p.sc_images)
	{
		VkImageViewCreateInfo vci = { VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
		vci.image = img;
		vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
		vci.format = p.sc_format;
		vci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		VkImageView view;
		if (vkCreateImageView(p.vk, &vci, nullptr, &view) != VK_SUCCESS)
			return false;
		p.sc_views.push_back(view);

		VkFramebufferCreateInfo fci = { VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
		fci.renderPass = p.render_pass;
		fci.attachmentCount = 1;
		fci.pAttachments = &view;
		fci.width = extent.width;
		fci.height = extent.height;
		fci.layers = 1;
		VkFramebuffer fb;
		if (vkCreateFramebuffer(p.vk, &fci, nullptr, &fb) != VK_SUCCESS)
			return false;
		p.sc_fbs.push_back(fb);
	}
	return true;
}

bool create_pipeline(Player &p)
{
	if (p.pipeline)
	{
		vkDestroyPipeline(p.vk, p.pipeline, nullptr);
		p.pipeline = VK_NULL_HANDLE;
	}

	VkPipelineShaderStageCreateInfo stages[2] = {
		{ VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, p.vert, "main", nullptr },
		{ VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, p.frag, "main", nullptr },
	};
	VkPipelineVertexInputStateCreateInfo vi = { VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
	VkPipelineInputAssemblyStateCreateInfo ia = { VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO };
	ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
	VkPipelineViewportStateCreateInfo vp = { VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO };
	vp.viewportCount = 1;
	vp.scissorCount = 1;
	VkPipelineRasterizationStateCreateInfo rs = { VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO };
	rs.polygonMode = VK_POLYGON_MODE_FILL;
	rs.cullMode = VK_CULL_MODE_NONE;
	rs.lineWidth = 1.0f;
	VkPipelineMultisampleStateCreateInfo ms = { VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO };
	ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
	VkPipelineColorBlendAttachmentState cba = {};
	cba.colorWriteMask = 0xf;
	VkPipelineColorBlendStateCreateInfo cb = { VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO };
	cb.attachmentCount = 1;
	cb.pAttachments = &cba;
	VkDynamicState dyn[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
	VkPipelineDynamicStateCreateInfo ds = { VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO };
	ds.dynamicStateCount = 2;
	ds.pDynamicStates = dyn;

	VkGraphicsPipelineCreateInfo gpci = { VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO };
	gpci.stageCount = 2;
	gpci.pStages = stages;
	gpci.pVertexInputState = &vi;
	gpci.pInputAssemblyState = &ia;
	gpci.pViewportState = &vp;
	gpci.pRasterizationState = &rs;
	gpci.pMultisampleState = &ms;
	gpci.pColorBlendState = &cb;
	gpci.pDynamicState = &ds;
	gpci.layout = p.pipe_layout;
	gpci.renderPass = p.render_pass;
	return vkCreateGraphicsPipelines(p.vk, VK_NULL_HANDLE, 1, &gpci, nullptr, &p.pipeline) == VK_SUCCESS;
}

void destroy_player(Player *p)
{
	if (!p)
		return;
	if (p->vk)
		vkDeviceWaitIdle(p->vk);

	if (p->test_encoder)
		pyrowave_encoder_destroy(p->test_encoder);
	if (p->decoder)
		pyrowave_decoder_destroy(p->decoder);

	if (p->vk)
	{
		destroy_swapchain(*p);
		if (p->pipeline) vkDestroyPipeline(p->vk, p->pipeline, nullptr);
		if (p->render_pass) vkDestroyRenderPass(p->vk, p->render_pass, nullptr);
		if (p->pipe_layout) vkDestroyPipelineLayout(p->vk, p->pipe_layout, nullptr);
		if (p->desc_pool) vkDestroyDescriptorPool(p->vk, p->desc_pool, nullptr);
		if (p->set_layout) vkDestroyDescriptorSetLayout(p->vk, p->set_layout, nullptr);
		if (p->sampler) vkDestroySampler(p->vk, p->sampler, nullptr);
		if (p->vert) vkDestroyShaderModule(p->vk, p->vert, nullptr);
		if (p->frag) vkDestroyShaderModule(p->vk, p->frag, nullptr);
		for (auto &pl : p->planes)
		{
			if (pl.view) vkDestroyImageView(p->vk, pl.view, nullptr);
			if (pl.image) vkDestroyImage(p->vk, pl.image, nullptr);
			if (pl.memory) vkFreeMemory(p->vk, pl.memory, nullptr);
		}
		for (auto &f : p->slots)
		{
			if (f.query_pool) vkDestroyQueryPool(p->vk, f.query_pool, nullptr);
			if (f.fence) vkDestroyFence(p->vk, f.fence, nullptr);
			if (f.acquire_sem) vkDestroySemaphore(p->vk, f.acquire_sem, nullptr);
			if (f.render_sem) vkDestroySemaphore(p->vk, f.render_sem, nullptr);
		}
		if (p->pool) vkDestroyCommandPool(p->vk, p->pool, nullptr);
	}
	if (p->surface && p->instance)
		vkDestroySurfaceKHR(p->instance, p->surface, nullptr);
	if (p->device)
		pyrowave_device_destroy(p->device);
	if (p->window)
		ANativeWindow_release(p->window);
	delete p;
}

Player *create_player(JNIEnv *env, jobject surface, int width, int height, bool chroma444)
{
	auto *p = new Player;
	p->width = width & ~1;
	p->height = height & ~1;
	p->chroma444 = chroma444;
	p->window = ANativeWindow_fromSurface(env, surface);
	if (!p->window)
	{
		LOGE("No ANativeWindow for the Surface");
		destroy_player(p);
		return nullptr;
	}

	const char *instance_ext[] = { "VK_KHR_surface", "VK_KHR_android_surface" };
	const char *device_ext[] = { "VK_KHR_swapchain" };
	pyrowave_result res = pyrowave_create_device_with_extensions(VK_QUEUE_GLOBAL_PRIORITY_MEDIUM,
	                                                             instance_ext, 2, device_ext, 1, &p->device);
	if (res != PYROWAVE_SUCCESS)
	{
		LOGE("pyrowave_create_device_with_extensions failed: %d", int(res));
		p->device = nullptr;
		destroy_player(p);
		return nullptr;
	}
	pyrowave_device_get_vk_device_handles(p->device, &p->instance, &p->gpu, &p->vk);
	p->fragment_path = pyrowave_decoder_device_prefers_fragment_path(p->device);
	// PyroWave prefers the fragment path on Mali. On the Mali-G57 MC2 (Redmi Pad 2) the compute path
	// needed 6 to 14% less GPU time than the fragment path and less CPU time per frame, so
	// compute is used on ARM GPUs. Other vendors keep what PyroWave prefers.
	VkPhysicalDeviceProperties vendor_props;
	vkGetPhysicalDeviceProperties(p->gpu, &vendor_props);
	if (vendor_props.vendorID == 0x13B5)
		p->fragment_path = false;
	// Debug switch to compare the two decode paths on a device:
	// adb shell setprop debug.selene.pyrowave.path compute|fragment (empty = the default above)
	char path_override[PROP_VALUE_MAX] = {};
	if (__system_property_get("debug.selene.pyrowave.path", path_override) > 0)
	{
		if (!strcmp(path_override, "compute"))
			p->fragment_path = false;
		else if (!strcmp(path_override, "fragment"))
			p->fragment_path = true;
		LOGI("Decode path forced by debug.selene.pyrowave.path=%s", path_override);
	}
	pyrowave_device_set_queue_type(p->device, VK_QUEUE_GRAPHICS_BIT);

	VkAndroidSurfaceCreateInfoKHR asci = { VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR };
	asci.window = p->window;
	// volk in the Granite submodule is built without the Android platform define, so load this one by hand.
	auto create_surface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
		vkGetInstanceProcAddr(p->instance, "vkCreateAndroidSurfaceKHR"));
	if (!create_surface || create_surface(p->instance, &asci, nullptr, &p->surface) != VK_SUCCESS)
	{
		LOGE("vkCreateAndroidSurfaceKHR failed");
		destroy_player(p);
		return nullptr;
	}

	// Graphics + compute family that can present.
	uint32_t qcount = 0;
	vkGetPhysicalDeviceQueueFamilyProperties(p->gpu, &qcount, nullptr);
	std::vector<VkQueueFamilyProperties> qprops(qcount);
	vkGetPhysicalDeviceQueueFamilyProperties(p->gpu, &qcount, qprops.data());
	bool found = false;
	for (uint32_t i = 0; i < qcount && !found; i++)
	{
		VkBool32 present = VK_FALSE;
		vkGetPhysicalDeviceSurfaceSupportKHR(p->gpu, i, p->surface, &present);
		if (present && (qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT))
		{
			p->family = i;
			found = true;
		}
	}
	if (!found)
	{
		LOGE("No graphics+compute queue family that can present");
		destroy_player(p);
		return nullptr;
	}
	vkGetDeviceQueue(p->vk, p->family, 0, &p->queue);

	// Timestamp queries give the GPU time of the decode. Not every mobile driver has them.
	VkPhysicalDeviceProperties gpu_props;
	vkGetPhysicalDeviceProperties(p->gpu, &gpu_props);
	uint32_t valid_bits = qprops[p->family].timestampValidBits;
	if (valid_bits != 0 && gpu_props.limits.timestampPeriod > 0.0f)
	{
		VkQueryPoolCreateInfo qpci = { VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO };
		qpci.queryType = VK_QUERY_TYPE_TIMESTAMP;
		qpci.queryCount = 3;
		p->timestamps = true;
		for (auto &f : p->slots)
			if (vkCreateQueryPool(p->vk, &qpci, nullptr, &f.query_pool) != VK_SUCCESS)
			{
				f.query_pool = VK_NULL_HANDLE;
				p->timestamps = false;
			}
		if (p->timestamps)
		{
			p->timestamp_mask = valid_bits >= 64 ? ~0ull : ((1ull << valid_bits) - 1);
			p->timestamp_period_ns = double(gpu_props.limits.timestampPeriod);
		}
		else
			for (auto &f : p->slots)
				if (f.query_pool)
				{
					vkDestroyQueryPool(p->vk, f.query_pool, nullptr);
					f.query_pool = VK_NULL_HANDLE;
				}
	}
	LOGI("GPU: %s, timestamp queries %s (%u valid bits, period %.2f ns)", gpu_props.deviceName,
	     p->timestamps ? "on" : "off", valid_bits, double(gpu_props.limits.timestampPeriod));

	pyrowave_decoder_create_info dci = {};
	dci.device = p->device;
	dci.width = p->width;
	dci.height = p->height;
	dci.chroma = chroma444 ? PYROWAVE_CHROMA_SUBSAMPLING_444 : PYROWAVE_CHROMA_SUBSAMPLING_420;
	dci.fragment_path = p->fragment_path;
	if (pyrowave_decoder_create(&dci, &p->decoder) != PYROWAVE_SUCCESS)
	{
		LOGE("pyrowave_decoder_create failed");
		p->decoder = nullptr;
		destroy_player(p);
		return nullptr;
	}

	// Decoded planes: written by the decoder (storage or color attachment), sampled for presentation.
	VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT |
		(p->fragment_path ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_STORAGE_BIT);
	uint32_t cw = chroma444 ? p->width : p->width / 2, ch = chroma444 ? p->height : p->height / 2;
	if (!create_plane(*p, p->planes[0], p->width, p->height, usage) ||
	    !create_plane(*p, p->planes[1], cw, ch, usage) ||
	    !create_plane(*p, p->planes[2], cw, ch, usage))
	{
		LOGE("Failed to create the decoded planes");
		destroy_player(p);
		return nullptr;
	}

	VkSamplerCreateInfo sci = { VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
	sci.magFilter = sci.minFilter = VK_FILTER_LINEAR;
	sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
	vkCreateSampler(p->vk, &sci, nullptr, &p->sampler);

	VkDescriptorSetLayoutBinding binds[3] = {};
	for (int i = 0; i < 3; i++)
	{
		binds[i].binding = uint32_t(i);
		binds[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		binds[i].descriptorCount = 1;
		binds[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
	}
	VkDescriptorSetLayoutCreateInfo dslci = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
	dslci.bindingCount = 3;
	dslci.pBindings = binds;
	vkCreateDescriptorSetLayout(p->vk, &dslci, nullptr, &p->set_layout);
	VkPipelineLayoutCreateInfo plci = { VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
	plci.setLayoutCount = 1;
	plci.pSetLayouts = &p->set_layout;
	vkCreatePipelineLayout(p->vk, &plci, nullptr, &p->pipe_layout);

	VkDescriptorPoolSize psize = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3 };
	VkDescriptorPoolCreateInfo dpci = { VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
	dpci.maxSets = 1;
	dpci.poolSizeCount = 1;
	dpci.pPoolSizes = &psize;
	vkCreateDescriptorPool(p->vk, &dpci, nullptr, &p->desc_pool);
	VkDescriptorSetAllocateInfo dsai = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
	dsai.descriptorPool = p->desc_pool;
	dsai.descriptorSetCount = 1;
	dsai.pSetLayouts = &p->set_layout;
	vkAllocateDescriptorSets(p->vk, &dsai, &p->desc_set);
	VkDescriptorImageInfo dii[3];
	VkWriteDescriptorSet writes[3] = {};
	for (int i = 0; i < 3; i++)
	{
		dii[i] = { p->sampler, p->planes[i].view, VK_IMAGE_LAYOUT_GENERAL };
		writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		writes[i].dstSet = p->desc_set;
		writes[i].dstBinding = uint32_t(i);
		writes[i].descriptorCount = 1;
		writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		writes[i].pImageInfo = &dii[i];
	}
	vkUpdateDescriptorSets(p->vk, 3, writes, 0, nullptr);

	VkShaderModuleCreateInfo smci = { VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
	smci.codeSize = sizeof(kVertSpv);
	smci.pCode = kVertSpv;
	vkCreateShaderModule(p->vk, &smci, nullptr, &p->vert);
	smci.codeSize = sizeof(kFragSpv);
	smci.pCode = kFragSpv;
	vkCreateShaderModule(p->vk, &smci, nullptr, &p->frag);

	VkCommandPoolCreateInfo cpci = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
	cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
	cpci.queueFamilyIndex = p->family;
	vkCreateCommandPool(p->vk, &cpci, nullptr, &p->pool);
	VkCommandBufferAllocateInfo cbai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
	cbai.commandPool = p->pool;
	cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
	cbai.commandBufferCount = 1;
	VkFenceCreateInfo fci = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
	VkSemaphoreCreateInfo semci = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
	for (auto &f : p->slots)
	{
		if (vkAllocateCommandBuffers(p->vk, &cbai, &f.cmd) != VK_SUCCESS ||
		    vkCreateFence(p->vk, &fci, nullptr, &f.fence) != VK_SUCCESS ||
		    vkCreateSemaphore(p->vk, &semci, nullptr, &f.acquire_sem) != VK_SUCCESS ||
		    vkCreateSemaphore(p->vk, &semci, nullptr, &f.render_sem) != VK_SUCCESS)
		{
			LOGE("Failed to create the per-frame Vulkan objects");
			destroy_player(p);
			return nullptr;
		}
	}

	if (!create_swapchain(*p) || !create_pipeline(*p))
	{
		LOGE("Failed to create the swapchain or the presentation pipeline");
		destroy_player(p);
		return nullptr;
	}

	LOGI("PyroWave player ready: %dx%d, %s decode path, swapchain %ux%u", p->width, p->height,
	     p->fragment_path ? "fragment" : "compute", p->sc_extent.width, p->sc_extent.height);
	return p;
}

// Returns 0 when presented, 1 when the frame was dropped, negative on errors.
int decode_and_present(Player &p, int64_t parse_ns = 0)
{
	// A frame that lost packets is still decoded: the missing blocks just come out blurry. The lowest
	// bands must be intact (otherwise the artifacts are extreme) and at least half of the blocks must
	// have arrived, which is looser than the 90% of the plain partial-frame check.
	if (!pyrowave_decoder_decode_is_ready_with_sideband(p.decoder, true, 1, 0.5f, nullptr, 0))
	{
		p.frames_dropped++;
		return 1;
	}

	// This slot was used two frames ago. Waiting for that frame keeps at most two frames on the GPU and
	// also makes it safe for PyroWave to recycle what it allocated for it.
	FrameSlot &f = p.slots[p.next_slot];
	p.next_slot = (p.next_slot + 1) % kFrameSlots;

	const int64_t t_start = now_ns();
	if (f.fence_pending)
	{
		vkWaitForFences(p.vk, 1, &f.fence, VK_TRUE, UINT64_MAX);
		f.fence_pending = false;
	}
	const int64_t t_fence = now_ns();

	// The previous frame is done on the GPU, so its timestamps can be read without waiting.
	int64_t gpu_decode_ns = -1, gpu_present_ns = -1;
	if (f.query_pending)
	{
		uint64_t ts[3] = {};
		if (vkGetQueryPoolResults(p.vk, f.query_pool, 0, 3, sizeof(ts), ts, sizeof(uint64_t),
		                          VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
		{
			gpu_decode_ns = int64_t(double((ts[1] - ts[0]) & p.timestamp_mask) * p.timestamp_period_ns);
			gpu_present_ns = int64_t(double((ts[2] - ts[1]) & p.timestamp_mask) * p.timestamp_period_ns);
		}
		f.query_pending = false;
	}

	uint32_t image_index = 0;
	VkResult r = vkAcquireNextImageKHR(p.vk, p.swapchain, UINT64_MAX, f.acquire_sem, VK_NULL_HANDLE, &image_index);
	if (r == VK_ERROR_OUT_OF_DATE_KHR)
	{
		vkDeviceWaitIdle(p.vk);
		if (!create_swapchain(p) || !create_pipeline(p))
			return -2;
		r = vkAcquireNextImageKHR(p.vk, p.swapchain, UINT64_MAX, f.acquire_sem, VK_NULL_HANDLE, &image_index);
	}
	if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR)
		return -3;

	const int64_t t_acquire = now_ns();

	vkResetFences(p.vk, 1, &f.fence);
	vkResetCommandBuffer(f.cmd, 0);
	VkCommandBufferBeginInfo cbbi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
	cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
	vkBeginCommandBuffer(f.cmd, &cbbi);
	if (f.query_pool)
		vkCmdResetQueryPool(f.cmd, f.query_pool, 0, 3);

	// Planes become writable by the decoder. Previous contents are not needed.
	VkImageMemoryBarrier bar[3] = {};
	for (int i = 0; i < 3; i++)
	{
		bar[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		bar[i].srcAccessMask = p.planes_initialized ? VK_ACCESS_SHADER_READ_BIT : 0;
		bar[i].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
		bar[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		bar[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
		bar[i].srcQueueFamilyIndex = bar[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		bar[i].image = p.planes[i].image;
		bar[i].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
	}
	vkCmdPipelineBarrier(f.cmd,
	                     p.planes_initialized ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
	                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
	                     0, 0, nullptr, 0, nullptr, 3, bar);
	p.planes_initialized = true;

	pyrowave_gpu_buffers buffers = {};
	for (int i = 0; i < 3; i++)
	{
		pyrowave_image_view &v = buffers.planes[i];
		v.image = p.planes[i].image;
		v.width = uint32_t(p.width);
		v.height = uint32_t(p.height);
		v.image_format = VK_FORMAT_R8_UNORM;
		v.view_format = VK_FORMAT_R8_UNORM;
		v.mip_level = 0;
		v.layer = 0;
		v.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
		v.swizzle = VK_COMPONENT_SWIZZLE_IDENTITY;
		v.layout = VK_IMAGE_LAYOUT_GENERAL;
	}
	if (f.query_pool)
		vkCmdWriteTimestamp(f.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, f.query_pool, 0);
	pyrowave_device_set_command_buffer(p.device, f.cmd);
	pyrowave_result dres = pyrowave_decoder_decode_gpu_buffer(p.decoder, nullptr, nullptr, &buffers);
	pyrowave_device_set_command_buffer(p.device, VK_NULL_HANDLE);
	if (dres != PYROWAVE_SUCCESS)
	{
		vkEndCommandBuffer(f.cmd);
		LOGE("decode_gpu_buffer failed: %d", int(dres));
		return -4;
	}

	if (f.query_pool)
		vkCmdWriteTimestamp(f.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, f.query_pool, 1);

	// Decoded data becomes readable by the presentation shader.
	VkMemoryBarrier mb = { VK_STRUCTURE_TYPE_MEMORY_BARRIER };
	mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
	mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
	vkCmdPipelineBarrier(f.cmd,
	                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
	                     VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);

	VkClearValue clear = {};
	VkRenderPassBeginInfo rpbi = { VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
	rpbi.renderPass = p.render_pass;
	rpbi.framebuffer = p.sc_fbs[image_index];
	rpbi.renderArea = { { 0, 0 }, p.sc_extent };
	rpbi.clearValueCount = 1;
	rpbi.pClearValues = &clear;
	vkCmdBeginRenderPass(f.cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);

	// Fit the stream inside the surface keeping its aspect ratio.
	float sw = float(p.sc_extent.width), sh = float(p.sc_extent.height);
	float scale = sw / float(p.width) < sh / float(p.height) ? sw / float(p.width) : sh / float(p.height);
	float vw = float(p.width) * scale, vh = float(p.height) * scale;
	VkViewport viewport = { (sw - vw) * 0.5f, (sh - vh) * 0.5f, vw, vh, 0.0f, 1.0f };
	VkRect2D scissor = { { 0, 0 }, p.sc_extent };
	vkCmdSetViewport(f.cmd, 0, 1, &viewport);
	vkCmdSetScissor(f.cmd, 0, 1, &scissor);
	vkCmdBindPipeline(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline);
	vkCmdBindDescriptorSets(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipe_layout, 0, 1, &p.desc_set, 0, nullptr);
	vkCmdDraw(f.cmd, 3, 1, 0, 0);
	vkCmdEndRenderPass(f.cmd);
	if (f.query_pool)
		vkCmdWriteTimestamp(f.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, f.query_pool, 2);
	vkEndCommandBuffer(f.cmd);
	const int64_t t_record = now_ns();

	VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
	VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
	si.waitSemaphoreCount = 1;
	si.pWaitSemaphores = &f.acquire_sem;
	si.pWaitDstStageMask = &wait_stage;
	si.commandBufferCount = 1;
	si.pCommandBuffers = &f.cmd;
	si.signalSemaphoreCount = 1;
	si.pSignalSemaphores = &f.render_sem;
	if (vkQueueSubmit(p.queue, 1, &si, f.fence) != VK_SUCCESS)
		return -5;
	f.fence_pending = true;
	f.query_pending = f.query_pool != VK_NULL_HANDLE;
	const int64_t t_submit = now_ns();

	VkPresentInfoKHR pi = { VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
	pi.waitSemaphoreCount = 1;
	pi.pWaitSemaphores = &f.render_sem;
	pi.swapchainCount = 1;
	pi.pSwapchains = &p.swapchain;
	pi.pImageIndices = &image_index;
	r = vkQueuePresentKHR(p.queue, &pi);
	if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR && r != VK_ERROR_OUT_OF_DATE_KHR)
		return -6;
	const int64_t t_present = now_ns();
	p.frames_presented++;

	{
		std::lock_guard<std::mutex> holder(p.stats_lock);
		StageTotals &st = p.stages;
		st.parse += parse_ns;
		st.fence_wait += t_fence - t_start;
		st.acquire += t_acquire - t_fence;
		st.record += t_record - t_acquire;
		st.submit += t_submit - t_record;
		st.present += t_present - t_submit;
		st.frames++;
		if (gpu_decode_ns >= 0)
		{
			st.gpu_decode += gpu_decode_ns;
			st.gpu_present += gpu_present_ns;
			st.gpu_frames++;
		}
	}
	return 0;
}

// Encodes a moving test pattern with PyroWave itself, then runs it through the normal decode and present path.
// Lets the presentation be verified on a device without a streaming server.
int self_test_frame(Player &p, int frame, int loss_percent)
{
	if (!p.test_encoder)
	{
		pyrowave_encoder_create_info eci = {};
		eci.device = p.device;
		eci.width = p.width;
		eci.height = p.height;
		eci.chroma = p.chroma444 ? PYROWAVE_CHROMA_SUBSAMPLING_444 : PYROWAVE_CHROMA_SUBSAMPLING_420;
		if (pyrowave_encoder_create(&eci, &p.test_encoder) != PYROWAVE_SUCCESS)
			return -10;
	}
	const int w = p.width, h = p.height;
	const int cw = p.chroma444 ? w : w / 2, ch = p.chroma444 ? h : h / 2;
	std::vector<uint8_t> y(size_t(w) * h), cb(size_t(cw) * ch), cr(size_t(cw) * ch);
	// Eight vertical bars with a moving white square, so orientation and colours are checkable.
	static const uint8_t bars[8][3] = { { 235, 128, 128 }, { 210, 16, 146 }, { 170, 166, 16 }, { 145, 54, 34 },
	                                    { 106, 202, 222 }, { 81, 90, 240 }, { 41, 240, 110 }, { 16, 128, 128 } };
	int sq = (frame * 8) % (w - 160);
	for (int j = 0; j < h; j++)
		for (int i = 0; i < w; i++)
		{
			int b = i * 8 / w;
			bool in_sq = i >= sq && i < sq + 160 && j >= h / 2 - 80 && j < h / 2 + 80;
			y[size_t(j) * w + i] = in_sq ? 235 : bars[b][0];
			if (i < cw && j < ch)
			{
				int ci = p.chroma444 ? i : i * 2, cj = p.chroma444 ? j : j * 2;
				int cbar = ci * 8 / w;
				bool csq = ci >= sq && ci < sq + 160 && cj >= h / 2 - 80 && cj < h / 2 + 80;
				cb[size_t(j) * cw + i] = csq ? 128 : bars[cbar][1];
				cr[size_t(j) * cw + i] = csq ? 128 : bars[cbar][2];
			}
		}

	pyrowave_cpu_buffer buf = {};
	buf.data[0] = y.data(); buf.data[1] = cb.data(); buf.data[2] = cr.data();
	buf.row_stride_in_bytes[0] = size_t(w); buf.row_stride_in_bytes[1] = buf.row_stride_in_bytes[2] = size_t(cw);
	buf.plane_size_in_bytes[0] = y.size(); buf.plane_size_in_bytes[1] = buf.plane_size_in_bytes[2] = cb.size();
	buf.width = w; buf.height = h;
	buf.format = p.chroma444 ? PYROWAVE_CPU_BUFFER_FORMAT_YUV444P : PYROWAVE_CPU_BUFFER_FORMAT_YUV420P;
	pyrowave_rate_control rc = { size_t(w) * h };
	if (pyrowave_encoder_encode_cpu(p.test_encoder, &buf, &rc) != PYROWAVE_SUCCESS)
		return -11;

	const size_t boundary = loss_percent > 0 ? 1200 : (1u << 20);
	size_t num = 0;
	if (pyrowave_encoder_compute_num_packets(p.test_encoder, boundary, &num) != PYROWAVE_SUCCESS)
		return -12;
	std::vector<pyrowave_packet> packets(num);
	std::vector<uint8_t> stream(size_t(w) * h * 2);
	size_t out = 0;
	if (pyrowave_encoder_packetize(p.test_encoder, packets.data(), boundary, &out, stream.data(), stream.size()) != PYROWAVE_SUCCESS)
		return -13;
	pyrowave_decoder_clear(p.decoder);
	for (size_t i = 0; i < out; i++)
	{
		// Simulated network loss. The first packet is kept: it carries the frame sequence header.
		if (loss_percent > 0 && i > 0 && int((uint32_t(i) * 2654435761u + uint32_t(frame) * 40503u) % 100) < loss_percent)
			continue;
		pyrowave_result pr = pyrowave_decoder_push_packet(p.decoder, stream.data() + packets[i].offset, packets[i].size);
		if (pr != PYROWAVE_SUCCESS)
		{
			LOGE("push_packet %zu/%zu (%zu bytes) failed: %d", i, out, packets[i].size, int(pr));
			return 1;
		}
	}
	return decode_and_present(p);
}
} // namespace

extern "C"
{
JNIEXPORT jlong JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeCreate(JNIEnv *env, jclass, jobject surface,
                                                                jint width, jint height, jboolean chroma444)
{
	return reinterpret_cast<jlong>(create_player(env, surface, width, height, chroma444 == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeSubmitFrame(JNIEnv *env, jclass, jlong handle,
                                                                      jbyteArray data, jint length)
{
	auto *p = reinterpret_cast<Player *>(handle);
	if (!p || !data || length <= 0)
		return -1;
	// Avoid copying the frame: the array stays pinned only while we parse the (small) bitstream into the decoder.
	jbyte *bytes = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(data, nullptr));
	if (!bytes)
		return -1;
	const int64_t parse_start = now_ns();
	pyrowave_decoder_clear(p->decoder);
	pyrowave_result push = pyrowave_decoder_push_packet(p->decoder, bytes, size_t(length));
	env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
	if (push != PYROWAVE_SUCCESS)
	{
		p->frames_dropped++;
		return 1;
	}
	return decode_and_present(*p, now_ns() - parse_start);
}

JNIEXPORT void JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
	destroy_player(reinterpret_cast<Player *>(handle));
}

JNIEXPORT jlong JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeFramesPresented(JNIEnv *, jclass, jlong handle)
{
	auto *p = reinterpret_cast<Player *>(handle);
	return p ? jlong(p->frames_presented) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeFramesDropped(JNIEnv *, jclass, jlong handle)
{
	auto *p = reinterpret_cast<Player *>(handle);
	return p ? jlong(p->frames_dropped) : 0;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeSelfTestFrame(JNIEnv *, jclass, jlong handle, jint frame, jint lossPercent)
{
	auto *p = reinterpret_cast<Player *>(handle);
	return p ? self_test_frame(*p, frame, lossPercent) : -1;
}

// Fills out with the average time of each stage since the last call, in milliseconds:
// parse, fence wait, acquire, record, submit, present, GPU decode, GPU present. The GPU values are -1
// when the device has no timestamp queries. Returns the number of frames averaged.
extern "C" JNIEXPORT jint JNICALL
Java_com_eumarcodev_selene_pyrowave_PyroWaveNative_nativeStageStats(JNIEnv *env, jclass, jlong handle, jfloatArray out)
{
	auto *p = reinterpret_cast<Player *>(handle);
	if (!p || !out || env->GetArrayLength(out) < 8)
		return 0;

	StageTotals st;
	{
		std::lock_guard<std::mutex> holder(p->stats_lock);
		st = p->stages;
		p->stages = StageTotals();
	}
	if (st.frames == 0)
		return 0;

	const double n = double(st.frames) * 1e6;
	jfloat ms[8] = {
		jfloat(double(st.parse) / n), jfloat(double(st.fence_wait) / n), jfloat(double(st.acquire) / n),
		jfloat(double(st.record) / n), jfloat(double(st.submit) / n), jfloat(double(st.present) / n),
		-1.0f, -1.0f,
	};
	if (st.gpu_frames > 0)
	{
		ms[6] = jfloat(double(st.gpu_decode) / (double(st.gpu_frames) * 1e6));
		ms[7] = jfloat(double(st.gpu_present) / (double(st.gpu_frames) * 1e6));
	}
	env->SetFloatArrayRegion(out, 0, 8, ms);
	LOGI("stages ms (%u frames): parse %.2f, fence wait %.2f, acquire %.2f, record %.2f, submit %.2f, present %.2f, GPU decode %.2f, GPU present %.2f",
	     st.frames, ms[0], ms[1], ms[2], ms[3], ms[4], ms[5], ms[6], ms[7]);
	return jint(st.frames);
}
