const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// Install sharp if not present
try {
    require.resolve('sharp');
} catch (e) {
    console.log("Installing sharp...");
    execSync('npm install sharp --no-save', { stdio: 'inherit' });
}

const sharp = require('sharp');

const SVG_DIR = path.join(__dirname, 'assets_src', 'icons');
const PNG_DIR = path.join(__dirname, 'src', 'main', 'resources', 'assets', 'wandscape', 'textures', 'gui', 'icons');

// Create directories if they don't exist
fs.mkdirSync(SVG_DIR, { recursive: true });
fs.mkdirSync(PNG_DIR, { recursive: true });

// Minimalist, crisp Material-style icons (solid white, 24x24 viewBox)
const icons = {
    // 1. Build (Hammer)
    'tab_build': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M19 3l2 2-11.5 11.5L6.5 13.5 18 2zM3 18l3-3 4 4-3 3-4-4z"/></svg>`,
    
    // 2. Road (Path / Shovel) - Using a simple road/path icon
    'tab_road': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M18.1 4.8C18 4.3 17.6 4 17.1 4H6.9C6.4 4 6 4.3 5.9 4.8L3 17.8V20c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-2.2l-2.9-13zM6.8 6h10.4l1.3 6H5.5l1.3-6zm-1.8 8h14l.8 3.5H4.2l.8-3.5z"/></svg>`,
    
    // 3. Editor (Pencil / Blueprint)
    'tab_editor': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>`,
    
    // 4. Stats (Bar Chart)
    'tab_stats': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z"/></svg>`,
    
    // 5. Colony (Castle / Flag)
    'icon_colony': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M12 12c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm6-1.8C18 6.57 15.35 4 12 4s-6 2.57-6 6.2c0 2.34 1.95 5.44 6 9.14 4.05-3.7 6-6.8 6-9.14zM12 2c4.2 0 8 3.22 8 8.2 0 3.32-2.67 7.25-8 11.8-5.33-4.55-8-8.48-8-11.8C4 5.22 7.8 2 12 2z"/></svg>`,
    
    // 6. Comfort (Bed)
    'icon_comfort': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M7 10c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm12-4H11v7H3V5H1v15h2v-3h18v3h2v-9c0-2.2-1.8-4-4-4z"/></svg>`,
    
    // 7. Magic (Star)
    'icon_magic': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/></svg>`,
    
    // 8. Wonder (Crown / Gem)
    'icon_wonder': `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="M12 2l-5.5 9h11L12 2zm0 3.84L13.93 9h-3.87L12 5.84zM17.5 13c-2.49 0-4.5 2.01-4.5 4.5s2.01 4.5 4.5 4.5 4.5-2.01 4.5-4.5-2.01-4.5-4.5-4.5zm0 7c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5zM3 21.5h8v-8H3v8zm2-6h4v4H5v-4z"/></svg>`
};

async function processIcons() {
    for (const [name, svgContent] of Object.entries(icons)) {
        const svgPath = path.join(SVG_DIR, `${name}.svg`);
        const pngPath = path.join(PNG_DIR, `${name}.png`);
        
        // Write SVG file
        fs.writeFileSync(svgPath, svgContent);
        console.log(`Saved SVG: ${svgPath}`);
        
        // Convert to PNG using sharp (resize to 64x64 for crisp downscaling in MC)
        await sharp(Buffer.from(svgContent))
            .resize(64, 64)
            .png()
            .toFile(pngPath);
            
        console.log(`Generated PNG: ${pngPath}`);
    }
    console.log("\\nAll icons generated successfully!");
}

processIcons().catch(err => {
    console.error("Error generating icons:", err);
});
