BUILD_DIR=source-firefox
SRC_DIR=source

all: clean copy swap-background add-id swap-agent

clean:
	rm -rf $(BUILD_DIR)

copy:
	cp -R $(SRC_DIR) $(BUILD_DIR)

swap-background:
	sed -i.bak 's/"service_worker": "service_worker.js"/"scripts": ["service_worker.js"]/' $(BUILD_DIR)/manifest.json
	rm -f $(BUILD_DIR)/manifest.json.bak

add-id:
	sed -i.bak 's/"manifest_version": 3/"manifest_version": 3,"browser_specific_settings": {"gecko": {"id": "id@antigram.org"}}/' $(BUILD_DIR)/manifest.json
	rm -f $(BUILD_DIR)/manifest.json.bak

swap-agent:
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/service_worker.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/background/service-worker.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/storage.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/tracker.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/budget.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/cooldown.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/friction.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/core/intervention.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/ui/reels-blur.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/ui/friction-modal.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/content-loader.js
	sed -i.bak 's/\bchrome\b/firefox/g' $(BUILD_DIR)/attentionos/content-main.js
	rm -f $(BUILD_DIR)/service_worker.js.bak
	rm -f $(BUILD_DIR)/attentionos/background/service-worker.js.bak
	rm -f $(BUILD_DIR)/attentionos/core/*.js.bak
	rm -f $(BUILD_DIR)/attentionos/ui/*.js.bak
	rm -f $(BUILD_DIR)/attentionos/*.js.bak